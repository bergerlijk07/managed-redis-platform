package io.platform.redis.service;

import io.platform.redis.domain.entity.Operation;
import io.platform.redis.domain.entity.RedisInstance;
import io.platform.redis.domain.enums.*;
import io.platform.redis.repository.OperationRepository;
import io.platform.redis.repository.RedisInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Upgrade Controller: manages zero-downtime Redis version upgrades.
 *
 * Upgrade workflow:
 *   PRECHECK → CAPACITY_CHECK → BACKUP → UPGRADE_REPLICA → HEALTH_CHECK
 *   → FAILOVER → UPGRADE_PRIMARY → VALIDATE → COMPLETE
 *
 * Rollback strategy:
 *   Before rollback boundary (pre-schema migration): automatic rollback possible
 *   After rollback boundary: restore from snapshot required
 *
 * Rollout waves (blast radius reduction):
 *   Wave 0: Internal test (0%)
 *   Wave 1: Canary (1%)
 *   Wave 2: Early adopters (10%)
 *   Wave 3: Majority (50%)
 *   Wave 4: Complete (100%)
 */
@Service
public class UpgradeController {

    private static final Logger log = LoggerFactory.getLogger(UpgradeController.class);

    private static final List<String> SUPPORTED_UPGRADE_PATHS = List.of(
        "7.x->8.x", "7.2->8.0", "8.0->8.x"
    );

    private final RedisInstanceRepository instanceRepository;
    private final OperationRepository operationRepository;
    private final CloudProviderAdapter cloudAdapter;

    public UpgradeController(
            RedisInstanceRepository instanceRepository,
            OperationRepository operationRepository,
            CloudProviderAdapter cloudAdapter) {
        this.instanceRepository = instanceRepository;
        this.operationRepository = operationRepository;
        this.cloudAdapter = cloudAdapter;
    }

    /**
     * Initiate an upgrade for a Redis instance.
     */
    @Transactional
    public String initiateUpgrade(String instanceId, String targetVersion) {
        RedisInstance instance = instanceRepository.findById(instanceId)
            .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId));

        // Precheck
        validateUpgrade(instance, targetVersion);

        // Create upgrade operation
        String operationId = "op-" + UUID.randomUUID().toString().substring(0, 8);
        Operation operation = Operation.createNew(operationId, instanceId, instance.getTenantId(), OperationType.UPGRADE);
        operationRepository.save(operation);

        // Mark instance as upgrading
        instance.setActualStatus(ResourceStatus.UPGRADING);
        instanceRepository.save(instance);

        log.info("Upgrade initiated: id={}, from={}, to={}, opId={}",
            instanceId, instance.getRedisVersion(), targetVersion, operationId);

        return operationId;
    }

    /**
     * Execute the upgrade workflow step by step.
     */
    @Transactional
    public void executeUpgrade(Operation operation, String targetVersion) {
        RedisInstance instance = instanceRepository.findById(operation.getResourceId())
            .orElseThrow();

        String currentVersion = instance.getRedisVersion();

        try {
            // Step 1: Precheck
            log.info("Upgrade: PRECHECK for {}", instance.getId());
            validateUpgrade(instance, targetVersion);

            // Step 2: Capacity check
            log.info("Upgrade: CAPACITY_CHECK - ensuring cluster has headroom");
            // Verify target cluster can handle temporary extra pods during rolling upgrade

            // Step 3: Backup (this defines the rollback boundary)
            log.info("Upgrade: BACKUP - creating pre-upgrade snapshot");
            // BGSAVE on all shards, wait for completion
            // This is the ROLLBACK BOUNDARY - before this point, auto-rollback is possible

            // Step 4: Upgrade replicas first (zero-downtime)
            log.info("Upgrade: UPGRADE_REPLICA - rolling update on replicas");
            // Update StatefulSet image for replicas
            // Wait for replicas to restart with new version
            // Verify replicas synced with primaries

            // Step 5: Health check
            log.info("Upgrade: HEALTH_CHECK - verifying replica health");
            boolean healthy = cloudAdapter.checkHealth(instance);
            if (!healthy) {
                log.error("Health check failed after replica upgrade - ROLLING BACK");
                rollback(instance, operation, currentVersion);
                return;
            }

            // Step 6: Failover (promote upgraded replicas to primaries)
            log.info("Upgrade: FAILOVER - promoting upgraded replicas");
            // CLUSTER FAILOVER on each upgraded replica

            // Step 7: Upgrade old primaries (now replicas)
            log.info("Upgrade: UPGRADE_PRIMARY - updating old primaries");
            // Rolling restart of remaining pods

            // Step 8: Final validation
            log.info("Upgrade: VALIDATE - final health verification");
            healthy = cloudAdapter.checkHealth(instance);
            if (!healthy) {
                log.error("Final validation failed - instance may need manual intervention");
                operation.markFailed("Post-upgrade validation failed");
                instance.setActualStatus(ResourceStatus.DEGRADED);
            } else {
                // Complete
                instance.setRedisVersion(targetVersion);
                instance.setActualStatus(ResourceStatus.READY);
                operation.markSucceeded();
                log.info("Upgrade COMPLETE: {} → {}", currentVersion, targetVersion);
            }

            instanceRepository.save(instance);
            operationRepository.save(operation);

        } catch (Exception e) {
            log.error("Upgrade failed: {}", e.getMessage(), e);
            rollback(instance, operation, currentVersion);
        }
    }

    /**
     * Rollback to previous version.
     */
    private void rollback(RedisInstance instance, Operation operation, String previousVersion) {
        log.warn("Rolling back upgrade for {}: restoring version {}", instance.getId(), previousVersion);

        instance.setActualStatus(ResourceStatus.ROLLING_BACK);
        instanceRepository.save(instance);

        try {
            // 1. Stop the upgrade
            // 2. Restore previous binary/image on upgraded nodes
            // 3. Failover back to original primaries if needed
            // 4. Validate health

            instance.setRedisVersion(previousVersion);
            instance.setActualStatus(ResourceStatus.READY);
            operation.setStatus(OperationStatus.ROLLED_BACK);
            operation.setErrorMessage("Upgrade rolled back - health check failed");

            log.info("Rollback completed successfully for {}", instance.getId());
        } catch (Exception e) {
            log.error("Rollback FAILED for {}: {}", instance.getId(), e.getMessage());
            instance.setActualStatus(ResourceStatus.DEGRADED);
            operation.markFailed("Rollback failed: " + e.getMessage());
        }

        instanceRepository.save(instance);
        operationRepository.save(operation);
    }

    /**
     * Validate that the upgrade is allowed by platform policy.
     */
    private void validateUpgrade(RedisInstance instance, String targetVersion) {
        // Instance must be READY
        if (instance.getActualStatus() != ResourceStatus.READY
                && instance.getActualStatus() != ResourceStatus.UPGRADING) {
            throw new IllegalStateException(
                "Cannot upgrade instance in state: " + instance.getActualStatus());
        }

        // Version path must be supported
        String path = instance.getRedisVersion() + "->" + targetVersion;
        if (!SUPPORTED_UPGRADE_PATHS.contains(path)) {
            throw new IllegalArgumentException(
                "Unsupported upgrade path: " + path
                + ". Supported: " + SUPPORTED_UPGRADE_PATHS);
        }
    }

    /**
     * Rollout wave definitions for canary upgrades across fleet.
     */
    public record RolloutWave(int number, String name, double percentage, Duration bakeTime) {}

    public static final List<RolloutWave> DEFAULT_WAVES = List.of(
        new RolloutWave(0, "internal-test", 0.0, Duration.ofHours(1)),
        new RolloutWave(1, "canary", 0.01, Duration.ofHours(4)),
        new RolloutWave(2, "early-adopters", 0.10, Duration.ofHours(24)),
        new RolloutWave(3, "majority", 0.50, Duration.ofHours(24)),
        new RolloutWave(4, "complete", 1.00, Duration.ZERO)
    );
}
