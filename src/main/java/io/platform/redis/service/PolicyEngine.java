package io.platform.redis.service;

import io.platform.redis.domain.entity.RedisInstance;
import io.platform.redis.domain.enums.AvailabilityMode;
import io.platform.redis.domain.enums.CloudProvider;
import io.platform.redis.domain.model.ResolvedTopology;
import io.platform.redis.exception.PolicyViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Policy Engine: converts customer intent into infrastructure topology.
 *
 * The customer says: "100Gi, multi-AZ, AWS us-east-1"
 * The policy engine decides:
 *   - 3 shards
 *   - 1 replica/shard
 *   - cache.r7g.2xlarge
 *   - gp3-encrypted storage
 *   - spread across 3 AZs
 *
 * This is where platform standards become architecture.
 */
@Service
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    private static final Set<String> SUPPORTED_VERSIONS = Set.of("7.x", "7.2", "8.x", "8.0");

    private static final Map<CloudProvider, Map<String, String>> INSTANCE_TYPES = Map.of(
        CloudProvider.AWS, Map.of(
            "small", "cache.r7g.large",
            "medium", "cache.r7g.xlarge",
            "large", "cache.r7g.2xlarge",
            "xlarge", "cache.r7g.4xlarge"
        ),
        CloudProvider.GCP, Map.of(
            "small", "n2-highmem-2",
            "medium", "n2-highmem-4",
            "large", "n2-highmem-8",
            "xlarge", "n2-highmem-16"
        ),
        CloudProvider.AZURE, Map.of(
            "small", "Standard_E2s_v5",
            "medium", "Standard_E4s_v5",
            "large", "Standard_E8s_v5",
            "xlarge", "Standard_E16s_v5"
        )
    );

    private static final Map<CloudProvider, String> STORAGE_CLASSES = Map.of(
        CloudProvider.AWS, "gp3-encrypted",
        CloudProvider.GCP, "pd-ssd",
        CloudProvider.AZURE, "managed-premium-v2"
    );

    /**
     * Validates the instance against platform policies.
     * Throws PolicyViolationException if any rule fails.
     */
    public void validate(RedisInstance instance) {
        List<String> violations = new ArrayList<>();

        // Rule: memory bounds
        int memoryGi = parseMemoryGi(instance.getMemory());
        if (memoryGi < 1 || memoryGi > 1024) {
            violations.add("Memory must be between 1Gi and 1024Gi");
        }

        // Rule: supported version
        if (instance.getRedisVersion() != null && !SUPPORTED_VERSIONS.contains(instance.getRedisVersion())) {
            violations.add("Unsupported Redis version: " + instance.getRedisVersion()
                + " (supported: " + SUPPORTED_VERSIONS + ")");
        }

        // Rule: multi-AZ requires persistence
        if (instance.getAvailabilityMode() == AvailabilityMode.MULTI_AZ && !instance.isPersistenceEnabled()) {
            violations.add("Multi-AZ deployments require persistence to be enabled for data safety");
        }

        // Rule: TLS required for public access
        if (instance.getNetworkAccess() == io.platform.redis.domain.enums.NetworkAccess.PUBLIC
                && !instance.isTlsEnabled()) {
            violations.add("Public network access requires TLS to be enabled");
        }

        if (!violations.isEmpty()) {
            throw new PolicyViolationException(violations);
        }
    }

    /**
     * Resolves customer intent into concrete infrastructure topology.
     */
    public ResolvedTopology resolveTopology(RedisInstance instance) {
        validate(instance);

        int memoryGi = parseMemoryGi(instance.getMemory());
        CloudProvider cloud = instance.getCloud();

        // Determine shard count based on memory tier
        int shards;
        String sizeTier;
        if (memoryGi <= 16) {
            shards = 1;
            sizeTier = "small";
        } else if (memoryGi <= 64) {
            shards = 3;
            sizeTier = "medium";
        } else if (memoryGi <= 256) {
            shards = 3;
            sizeTier = "large";
        } else {
            shards = 6;
            sizeTier = "xlarge";
        }

        // Determine replicas based on availability mode
        int replicasPerShard;
        int azCount;
        if (instance.getAvailabilityMode() == AvailabilityMode.MULTI_AZ) {
            replicasPerShard = 1;
            azCount = 3;
        } else {
            replicasPerShard = 0;
            azCount = 1;
        }

        // Resolve instance type
        String instanceType = INSTANCE_TYPES
            .getOrDefault(cloud, Map.of())
            .getOrDefault(sizeTier, "unknown");

        // Resolve storage
        String storageClass = instance.isPersistenceEnabled()
            ? STORAGE_CLASSES.getOrDefault(cloud, "default")
            : null;

        String storageSize = instance.isPersistenceEnabled()
            ? (memoryGi / shards * 2) + "Gi"  // 2x memory per shard
            : null;

        // Resolve AZs
        List<String> azs = resolveAZs(cloud, instance.getRegion(), azCount);

        log.info("Policy resolved topology: memory={}Gi → shards={}, replicas={}, instanceType={}, azs={}",
            memoryGi, shards, replicasPerShard, instanceType, azs);

        return new ResolvedTopology(shards, replicasPerShard, instanceType, storageClass, storageSize, azs);
    }

    // === Helpers ===

    private int parseMemoryGi(String memory) {
        if (memory == null || memory.isBlank()) return 0;
        String numeric = memory.replaceAll("[^0-9]", "");
        try {
            return Integer.parseInt(numeric);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<String> resolveAZs(CloudProvider cloud, String region, int count) {
        // In production, query cloud API for available AZs
        String[] suffixes = {"a", "b", "c", "d"};
        List<String> azs = new ArrayList<>();
        for (int i = 0; i < count && i < suffixes.length; i++) {
            azs.add(region + suffixes[i]);
        }
        return azs;
    }
}
