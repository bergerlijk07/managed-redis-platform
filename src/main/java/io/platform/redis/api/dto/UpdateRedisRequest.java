package io.platform.redis.api.dto;

import jakarta.validation.constraints.Pattern;

/**
 * UpdateRedisRequest allows customers to scale or modify an existing Redis instance.
 * All fields are optional — only provided fields are updated.
 *
 * Examples:
 *   Scale up:    { "memory": "200Gi" }
 *   Upgrade:     { "redisVersion": "8.x" }
 *   Multi-field: { "memory": "200Gi", "persistence": true, "tls": true }
 */
public record UpdateRedisRequest(

    @Pattern(regexp = "\\d+Gi", message = "memory must be in format like 200Gi")
    String memory,

    @Pattern(regexp = "\\d+\\.\\w+", message = "version must be like 8.x or 7.2")
    String redisVersion,

    Boolean persistence,

    @Pattern(regexp = "private|public", message = "networkAccess must be private or public")
    String networkAccess,

    Boolean encryptionAtRest,

    Boolean tls,

    @Pattern(regexp = "single-az|multi-az", message = "availability must be single-az or multi-az")
    String availability
) {}
