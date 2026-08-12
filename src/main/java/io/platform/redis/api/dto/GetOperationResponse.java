package io.platform.redis.api.dto;

import java.time.Instant;

public record GetOperationResponse(
    String id,
    String resourceId,
    String type,
    String status,
    String phase,
    String error,
    Instant createdAt,
    Instant updatedAt
) {}
