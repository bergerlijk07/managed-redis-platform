package io.platform.redis.service;

import io.platform.redis.domain.entity.RedisInstance;
import io.platform.redis.domain.enums.AvailabilityMode;
import io.platform.redis.domain.enums.CloudProvider;
import io.platform.redis.domain.model.Cluster;
import io.platform.redis.domain.model.PlacementResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Placement Engine: selects the optimal target cluster for a workload.
 *
 * Decision flow:
 *   eligible clusters
 *     → filter unhealthy
 *     → filter insufficient capacity
 *     → filter policy mismatch (region, cloud, AZ count)
 *     → score remaining clusters
 *     → select best
 */
@Service
public class PlacementEngine {

    private static final Logger log = LoggerFactory.getLogger(PlacementEngine.class);

    // In production, this would come from a cluster registry service
    private final List<Cluster> availableClusters = defaultClusters();

    /**
     * Places a Redis instance on the best available cluster.
     */
    public PlacementResult place(RedisInstance instance) {
        List<Cluster> eligible = filter(instance);

        if (eligible.isEmpty()) {
            throw new IllegalStateException(
                "No eligible clusters for cloud=" + instance.getCloud()
                + " region=" + instance.getRegion());
        }

        // Score and select best
        PlacementResult best = eligible.stream()
            .map(cluster -> score(cluster, instance))
            .max(Comparator.comparingDouble(PlacementResult::score))
            .orElseThrow();

        log.info("Placement decision: resourceId={} → cluster={} (score={})",
            instance.getId(), best.clusterName(), best.score());

        return best;
    }

    /**
     * Filter clusters that don't match basic requirements.
     */
    private List<Cluster> filter(RedisInstance instance) {
        return availableClusters.stream()
            .filter(c -> c.cloud() == instance.getCloud())
            .filter(c -> c.region().equals(instance.getRegion()))
            .filter(Cluster::healthy)
            .filter(Cluster::hasCapacity)
            .filter(c -> {
                // Multi-AZ requires at least 3 AZs in the cluster
                if (instance.getAvailabilityMode() == AvailabilityMode.MULTI_AZ) {
                    return c.availabilityZones().size() >= 3;
                }
                return true;
            })
            .toList();
    }

    /**
     * Score a cluster for this workload. Higher = better.
     */
    private PlacementResult score(Cluster cluster, RedisInstance instance) {
        double score = 0;

        // Capacity headroom (40 points max)
        double capacityScore = (1.0 - cluster.tenantUtilization()) * 40;
        score += capacityScore;

        // AZ count (10 points per AZ, max 30)
        double azScore = Math.min(cluster.availabilityZones().size() * 10.0, 30.0);
        score += azScore;

        // Isolation: prefer less-utilized clusters (30 points max)
        double isolationScore = (1.0 - cluster.tenantUtilization()) * 30;
        score += isolationScore;

        String reason = String.format("capacity=%.1f az=%.1f isolation=%.1f",
            capacityScore, azScore, isolationScore);

        return new PlacementResult(cluster.id(), cluster.name(), score, reason);
    }

    // === Default cluster registry (for development) ===

    private static List<Cluster> defaultClusters() {
        return List.of(
            new Cluster("eks-use1-prod-01", "aws-use1-prod-01", CloudProvider.AWS,
                "us-east-1", 2048,
                List.of("us-east-1a", "us-east-1b", "us-east-1c"),
                true, "1.29", 45, 100),
            new Cluster("eks-use1-prod-02", "aws-use1-prod-02", CloudProvider.AWS,
                "us-east-1", 4096,
                List.of("us-east-1a", "us-east-1b", "us-east-1c"),
                true, "1.29", 20, 100),
            new Cluster("eks-usw2-prod-01", "aws-usw2-prod-01", CloudProvider.AWS,
                "us-west-2", 2048,
                List.of("us-west-2a", "us-west-2b", "us-west-2c"),
                true, "1.29", 60, 100),
            new Cluster("gke-use1-prod-01", "gcp-use1-prod-01", CloudProvider.GCP,
                "us-east1", 2048,
                List.of("us-east1-b", "us-east1-c", "us-east1-d"),
                true, "1.29", 30, 80)
        );
    }
}
