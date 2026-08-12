package io.platform.redis.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * CreateRedisRequest is the customer's intent declaration.
 * Notice: no infrastructure details (VPC, instance types, etc.)
 */
public record CreateRedisRequest(
    @NotBlank(message = "name is required")
    String name,

    @NotBlank(message = "cloud is required")
    @Pattern(regexp = "aws|gcp|azure", message = "cloud must be aws, gcp, or azure")
    String cloud,

    @NotBlank(message = "region is required")
    String region,

    @NotBlank(message = "memory is required")
    @Pattern(regexp = "\\d+Gi", message = "memory must be in format like 100Gi")
    String memory,

    String availability,  // "single-az" or "multi-az"
    boolean persistence,
    String networkAccess, // "private" or "public"
    boolean encryptionAtRest,
    boolean tls,

    @Pattern(regexp = "\\d+\\.\\w+", message = "version must be like 8.x or 7.2")
    String redisVersion
) {}
