package io.platform.redis.domain.model;

import io.platform.redis.domain.enums.CloudProvider;
import java.util.List;

/**
 * Cluster represents a target Kubernetes cluster for workload placement.
 * Not persisted in the DB - typically loaded from a cluster registry.
 */
public record Cluster(
    String id,
    String name,
    CloudProvider cloud,
    String region,
    long availableMemoryGi,
    List<String> availabilityZones,
    boolean healthy,
    String kubernetesVersion,
    int tenantCount,
    int maxTenants
) {
    public double tenantUtilization() {
        if (maxTenants == 0) return 1.0;
        return (double) tenantCount / maxTenants;
    }

    public boolean hasCapacity() {
        return tenantCount < maxTenants;
    }
}
