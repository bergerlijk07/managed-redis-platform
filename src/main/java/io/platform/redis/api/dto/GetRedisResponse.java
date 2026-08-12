package io.platform.redis.api.dto;

import java.time.Instant;

public record GetRedisResponse(
    String id,
    String name,
    String cloud,
    String region,
    String memory,
    String desiredStatus,
    String actualStatus,
    String endpoint,
    Integer shards,
    Integer replicasPerShard,
    Instant createdAt
) {}
