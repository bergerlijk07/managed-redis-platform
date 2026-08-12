package io.platform.redis.domain.model;

/**
 * PlacementResult holds the selected target cluster and the scoring breakdown.
 */
public record PlacementResult(
    String clusterId,
    String clusterName,
    double score,
    String reason
) {}
