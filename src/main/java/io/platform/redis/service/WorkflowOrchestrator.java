package io.platform.redis.service;

import io.platform.redis.domain.entity.Operation;
import io.platform.redis.domain.entity.RedisInstance;
import io.platform.redis.domain.enums.ResourceStatus;
import io.platform.redis.domain.enums.WorkflowPhase;
import io.platform.redis.domain.model.PlacementResult;
import io.platform.redis.repository.OperationRepository;
import io.platform.redis.repository.RedisInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workflow Orchestrator: drives lifecycle operations as a durable state machine.
 *
 * Instead of one giant function:
 *   createRedis() { createNetwork(); createStorage(); deployRedis(); ... }
 *
 * We model provisioning as persisted phases:
 *   VALIDATING → ALLOCATING → NETWORK_SETUP → STORAGE_SETUP → DEPLOYING → CONFIGURING → HEALTH_CHECK → READY
 *
 * If the process crashes at STORAGE_SETUP, it resumes from there on restart.
 * Each phase is idempotent - safe to retry.
 */
@Service
public class WorkflowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOrchestrator.class);

    private final RedisInstanceRepository instanceRepository;
    private final OperationRepository operationRepository;
    private final PlacementEngine placementEngine;
    private final CloudProviderAdapter cloudAdapter;

    public WorkflowOrchestrator(
            RedisInstanceRepository instanceRepository,
            OperationRepository operationRepository,
            PlacementEngine placementEngine,
            CloudProviderAdapter cloudAdapter) {
        this.instanceRepository = instanceRepository;
        this.operationRepository = operationRepository;
        this.placementEngine = placementEngine;
        this.cloudAdapter = cloudAdapter;
    }

    /**
     * Advances the operation by executing the current phase and transitioning to the next.
     * Each call advances ONE phase - the reconciliation loop calls this repeatedly.
     */
    @Transactional
    public void advanceOperation(Operation operation) {
        RedisInstance instance = instanceRepository.findById(operation.getResourceId())
            .orElseThrow(() -> new IllegalStateException("Instance not found: " + operation.getResourceId()));

        MDC.put("operationId", operation.getId());
        MDC.put("resourceId", instance.getId());
        MDC.put("tenantId", instance.getTenantId());
        MDC.put("phase", operation.getPhase().name());

        try {
            WorkflowPhase nextPhase = executePhase(operation.getPhase(), instance, operation);
            operation.advancePhase(nextPhase);

            // Terminal states
            if (nextPhase == WorkflowPhase.READY) {
                operation.markSucceeded();
                instance.setActualStatus(ResourceStatus.READY);
                instance.setEndpoint(instance.getId() + ".redis.platform.internal");
                log.info("Workflow completed successfully");
            } else if (nextPhase == WorkflowPhase.DELETE_COMPLETE) {
                operation.markSucceeded();
                instance.setActualStatus(ResourceStatus.DELETED);
                log.info("Deletion workflow completed");
            } else if (nextPhase == WorkflowPhase.FAILED) {
                operation.markFailed("Phase execution failed");
                instance.setActualStatus(ResourceStatus.FAILED);
            } else {
                // In-progress states
                if (operation.getPhase() == WorkflowPhase.SCALING || operation.getPhase() == WorkflowPhase.MODIFYING) {
                    // Keep SCALING/MODIFYING status while in progress
                } else {
                    instance.setActualStatus(ResourceStatus.PROVISIONING);
                }
            }

            instanceRepository.save(instance);
            operationRepository.save(operation);

            log.info("Phase transition: {} → {}", operation.getPhase(), nextPhase);

        } catch (Exception e) {
            log.error("Workflow phase failed: {}", e.getMessage(), e);
            operation.markFailed(e.getMessage());
            instance.setActualStatus(ResourceStatus.FAILED);
            instanceRepository.save(instance);
            operationRepository.save(operation);
        } finally {
            MDC.clear();
        }
    }

    /**
     * Executes a single phase and returns the next phase.
     * Each phase handler is idempotent.
     */
    private WorkflowPhase executePhase(WorkflowPhase phase, RedisInstance instance, Operation operation) {
        return switch (phase) {
            case VALIDATING -> handleValidating(instance);
            case ALLOCATING -> handleAllocating(instance);
            case NETWORK_SETUP -> handleNetworkSetup(instance);
            case STORAGE_SETUP -> handleStorageSetup(instance);
            case DEPLOYING -> handleDeploying(instance);
            case CONFIGURING -> handleConfiguring(instance);
            case VALIDATING_HEALTH -> handleHealthCheck(instance);
            case SCALING -> handleScaling(instance);
            case MODIFYING -> handleModifying(instance);
            case DELETING -> handleDeleting(instance);
            case DELETE_NETWORK -> handleDeleteNetwork(instance);
            case DELETE_STORAGE -> handleDeleteStorage(instance);
            default -> WorkflowPhase.FAILED;
        };
    }

    // ===== Phase Handlers (each is idempotent) =====

    private WorkflowPhase handleValidating(RedisInstance instance) {
        log.info("Validating instance configuration");

        if (instance.getMemory() == null || instance.getMemory().isBlank()) {
            throw new IllegalStateException("Memory not specified");
        }
        if (instance.getShards() == null || instance.getShards() == 0) {
            throw new IllegalStateException("Topology not resolved - shards is 0");
        }

        return WorkflowPhase.ALLOCATING;
    }

    private WorkflowPhase handleAllocating(RedisInstance instance) {
        log.info("Running placement engine");

        if (instance.getClusterId() != null) {
            log.info("Cluster already allocated: {}", instance.getClusterId());
            return WorkflowPhase.NETWORK_SETUP;
        }

        PlacementResult result = placementEngine.place(instance);
        instance.setClusterId(result.clusterId());

        log.info("Cluster allocated: {} (score={}, reason={})",
            result.clusterName(), result.score(), result.reason());

        return WorkflowPhase.NETWORK_SETUP;
    }

    private WorkflowPhase handleNetworkSetup(RedisInstance instance) {
        log.info("Provisioning network: access={}", instance.getNetworkAccess());

        // Delegate to cloud provider adapter
        cloudAdapter.provisionNetwork(instance);

        return WorkflowPhase.STORAGE_SETUP;
    }

    private WorkflowPhase handleStorageSetup(RedisInstance instance) {
        if (!instance.isPersistenceEnabled()) {
            log.info("Persistence disabled - skipping storage setup");
            return WorkflowPhase.DEPLOYING;
        }

        log.info("Provisioning storage: class={}, size={}",
            instance.getStorageClass(), instance.getStorageSize());

        cloudAdapter.provisionStorage(instance);

        return WorkflowPhase.DEPLOYING;
    }

    private WorkflowPhase handleDeploying(RedisInstance instance) {
        log.info("Deploying Redis: shards={}, replicas/shard={}, cluster={}",
            instance.getShards(), instance.getReplicasPerShard(), instance.getClusterId());

        // In production: create ManagedRedis CRD on target cluster via Kubernetes client
        cloudAdapter.deployRedis(instance);

        return WorkflowPhase.CONFIGURING;
    }

    private WorkflowPhase handleConfiguring(RedisInstance instance) {
        log.info("Configuring Redis: tls={}, encryption={}",
            instance.isTlsEnabled(), instance.isEncryptionAtRest());

        // TLS certificates, ACLs, monitoring registration, DNS
        cloudAdapter.configureRedis(instance);

        return WorkflowPhase.VALIDATING_HEALTH;
    }

    private WorkflowPhase handleHealthCheck(RedisInstance instance) {
        log.info("Running health validation");

        // In production: verify Redis responds, replication healthy, latency OK
        boolean healthy = cloudAdapter.checkHealth(instance);

        if (!healthy) {
            log.warn("Health check failed for {}", instance.getId());
            return WorkflowPhase.FAILED;
        }

        return WorkflowPhase.READY;
    }

    private WorkflowPhase handleDeleting(RedisInstance instance) {
        log.info("Deleting Redis resources");
        cloudAdapter.deleteRedis(instance);
        return WorkflowPhase.DELETE_NETWORK;
    }

    private WorkflowPhase handleDeleteNetwork(RedisInstance instance) {
        log.info("Cleaning up network resources");
        cloudAdapter.deleteNetwork(instance);
        return WorkflowPhase.DELETE_STORAGE;
    }

    private WorkflowPhase handleDeleteStorage(RedisInstance instance) {
        log.info("Cleaning up storage resources");
        cloudAdapter.deleteStorage(instance);
        return WorkflowPhase.DELETE_COMPLETE;
    }

    // ===== Scaling & Modification Phase Handlers =====

    private WorkflowPhase handleScaling(RedisInstance instance) {
        log.info("Scaling Redis instance: id={}, newMemory={}, newShards={}, newInstanceType={}",
            instance.getId(), instance.getMemory(), instance.getShards(), instance.getInstanceType());

        // Scale the cloud resources (ElastiCache node type + shard count)
        cloudAdapter.scaleRedis(instance);

        // Update the CRD on Kubernetes so the operator is aware of new topology
        // (handled via KubernetesOperatorService in the reconciler)

        // After scaling, validate health before marking READY
        return WorkflowPhase.VALIDATING_HEALTH;
    }

    private WorkflowPhase handleModifying(RedisInstance instance) {
        log.info("Modifying Redis instance: id={}, version={}, tls={}, persistence={}",
            instance.getId(), instance.getRedisVersion(), instance.isTlsEnabled(), instance.isPersistenceEnabled());

        // Modify cloud resources (ElastiCache configuration)
        cloudAdapter.modifyRedis(instance);

        // After modification, validate health before marking READY
        return WorkflowPhase.VALIDATING_HEALTH;
    }
}
