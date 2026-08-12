package io.platform.redis.api.controller;

import io.platform.redis.api.dto.*;
import io.platform.redis.domain.entity.Operation;
import io.platform.redis.domain.entity.RedisInstance;
import io.platform.redis.domain.enums.*;
import io.platform.redis.domain.model.ResolvedTopology;
import io.platform.redis.service.PolicyEngine;
import io.platform.redis.repository.OperationRepository;
import io.platform.redis.repository.RedisInstanceRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Platform API for Redis Instances.
 * 
 * Design principles:
 * - Versioned (/v1)
 * - Async for long operations (202 Accepted)
 * - Idempotent (Idempotency-Key header)
 * - Tenant-scoped (X-Tenant-ID header)
 * - Exposes platform model, NOT cloud internals
 */
@RestController
@RequestMapping("/v1/redis-instances")
public class RedisInstanceController {

    private static final Logger log = LoggerFactory.getLogger(RedisInstanceController.class);

    private final RedisInstanceRepository instanceRepository;
    private final OperationRepository operationRepository;
    private final PolicyEngine policyEngine;

    public RedisInstanceController(
            RedisInstanceRepository instanceRepository,
            OperationRepository operationRepository,
            PolicyEngine policyEngine) {
        this.instanceRepository = instanceRepository;
        this.operationRepository = operationRepository;
        this.policyEngine = policyEngine;
    }

    /**
     * POST /v1/redis-instances
     * Creates a new managed Redis instance.
     * Returns 202 Accepted with an operation ID for tracking.
     */
    @PostMapping
    public ResponseEntity<CreateRedisResponse> create(
            @Valid @RequestBody CreateRedisRequest request,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "default") String tenantId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {

        MDC.put("tenantId", tenantId);
        MDC.put("requestId", requestId != null ? requestId : UUID.randomUUID().toString());

        // Idempotency: if same tenant+name exists, return existing
        if (idempotencyKey != null) {
            var existing = instanceRepository.findByTenantIdAndName(tenantId, request.name());
            if (existing.isPresent()) {
                log.info("Idempotent request - returning existing instance: {}", existing.get().getId());
                return ResponseEntity.ok(new CreateRedisResponse(
                    existing.get().getId(),
                    "",
                    existing.get().getActualStatus().name()
                ));
            }
        }

        // Generate IDs
        String instanceId = "redis-" + UUID.randomUUID().toString().substring(0, 8);
        String operationId = "op-" + UUID.randomUUID().toString().substring(0, 8);

        // Build desired state entity
        RedisInstance instance = new RedisInstance();
        instance.setId(instanceId);
        instance.setName(request.name());
        instance.setTenantId(tenantId);
        instance.setCloud(CloudProvider.valueOf(request.cloud().toUpperCase()));
        instance.setRegion(request.region());
        instance.setMemory(request.memory());
        instance.setRedisVersion(request.redisVersion() != null ? request.redisVersion() : "8.x");
        instance.setAvailabilityMode(parseAvailability(request.availability()));
        instance.setPersistenceEnabled(request.persistence());
        instance.setNetworkAccess(parseNetworkAccess(request.networkAccess()));
        instance.setEncryptionAtRest(request.encryptionAtRest());
        instance.setTlsEnabled(request.tls());
        instance.setDesiredStatus(ResourceStatus.READY);
        instance.setActualStatus(ResourceStatus.REQUESTED);

        // Policy Engine: resolve topology from intent
        ResolvedTopology topology = policyEngine.resolveTopology(instance);
        instance.setShards(topology.shards());
        instance.setReplicasPerShard(topology.replicasPerShard());
        instance.setInstanceType(topology.instanceType());
        instance.setStorageClass(topology.storageClass());
        instance.setStorageSize(topology.storageSize());
        instance.setAvailabilityZonesList(topology.availabilityZones());

        // Persist desired state (BEFORE any infrastructure work)
        instanceRepository.save(instance);

        // Create async operation record
        Operation operation = Operation.createNew(operationId, instanceId, tenantId, OperationType.CREATE);
        operationRepository.save(operation);

        log.info("Redis instance creation requested: id={}, name={}, cloud={}, region={}, memory={}",
            instanceId, request.name(), request.cloud(), request.region(), request.memory());

        MDC.clear();

        // 202 Accepted - provisioning is asynchronous
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(new CreateRedisResponse(instanceId, operationId, "PROVISIONING"));
    }

    /**
     * GET /v1/redis-instances/:id
     */
    @GetMapping("/{id}")
    public ResponseEntity<GetRedisResponse> get(
            @PathVariable String id,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "default") String tenantId) {

        return instanceRepository.findById(id)
            .filter(inst -> inst.getTenantId().equals(tenantId))
            .map(inst -> ResponseEntity.ok(new GetRedisResponse(
                inst.getId(),
                inst.getName(),
                inst.getCloud().name().toLowerCase(),
                inst.getRegion(),
                inst.getMemory(),
                inst.getDesiredStatus().name(),
                inst.getActualStatus().name(),
                inst.getEndpoint(),
                inst.getShards(),
                inst.getReplicasPerShard(),
                inst.getCreatedAt()
            )))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /v1/redis-instances
     */
    @GetMapping
    public ResponseEntity<List<GetRedisResponse>> list(
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "default") String tenantId) {

        List<GetRedisResponse> instances = instanceRepository.findByTenantId(tenantId).stream()
            .map(inst -> new GetRedisResponse(
                inst.getId(),
                inst.getName(),
                inst.getCloud().name().toLowerCase(),
                inst.getRegion(),
                inst.getMemory(),
                inst.getDesiredStatus().name(),
                inst.getActualStatus().name(),
                inst.getEndpoint(),
                inst.getShards(),
                inst.getReplicasPerShard(),
                inst.getCreatedAt()
            ))
            .toList();

        return ResponseEntity.ok(instances);
    }

    /**
     * DELETE /v1/redis-instances/:id
     * Marks desired state as DELETED. Actual deletion is async.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable String id,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "default") String tenantId) {

        return instanceRepository.findById(id)
            .filter(inst -> inst.getTenantId().equals(tenantId))
            .map(inst -> {
                // Update desired state — control plane will reconcile
                inst.setDesiredStatus(ResourceStatus.DELETED);
                instanceRepository.save(inst);

                String operationId = "op-" + UUID.randomUUID().toString().substring(0, 8);
                Operation op = Operation.createNew(operationId, id, tenantId, OperationType.DELETE);
                op.setPhase(WorkflowPhase.DELETING);
                operationRepository.save(op);

                log.info("Redis instance deletion requested: id={}", id);

                return ResponseEntity.accepted().body(new CreateRedisResponse(id, operationId, "DELETING"));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    // === Helpers ===

    private AvailabilityMode parseAvailability(String input) {
        if ("multi-az".equalsIgnoreCase(input) || "MULTI_AZ".equalsIgnoreCase(input)) {
            return AvailabilityMode.MULTI_AZ;
        }
        return AvailabilityMode.SINGLE_AZ;
    }

    private NetworkAccess parseNetworkAccess(String input) {
        if ("public".equalsIgnoreCase(input)) {
            return NetworkAccess.PUBLIC;
        }
        return NetworkAccess.PRIVATE;
    }
}
