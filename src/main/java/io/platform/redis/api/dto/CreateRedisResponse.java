package io.platform.redis.api.dto;

import java.time.Instant;

public record CreateRedisResponse(
    String id,
    String operationId,
    String status
) {}
