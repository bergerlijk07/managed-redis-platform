package io.platform.redis.domain.model;

import java.util.List;

/**
 * ResolvedTopology is the output of the Policy Engine.
 * Maps customer intent (100Gi + multi-az) into concrete infrastructure topology.
 */
public record ResolvedTopology(
    int shards,
    int replicasPerShard,
    String instanceType,
    String storageClass,
    String storageSize,
    List<String> availabilityZones
) {}
