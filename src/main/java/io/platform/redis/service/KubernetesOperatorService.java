package io.platform.redis.service;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.platform.redis.domain.entity.RedisInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;

/**
 * Kubernetes Operator Integration using Fabric8 Kubernetes Client.
 *
 * This service manages the ManagedRedis CRD on target EKS clusters.
 * It uses Fabric8 to:
 * 1. Create/update ManagedRedis custom resources
 * 2. Read status updates from the operator
 * 3. Manage tenant namespaces
 *
 * The operator running on each cluster then reconciles:
 *   ManagedRedis CRD → StatefulSets + Services + PVCs + NetworkPolicies + PDBs + Monitoring
 */
@Service
public class KubernetesOperatorService {

    private static final Logger log = LoggerFactory.getLogger(KubernetesOperatorService.class);

    private static final String CRD_GROUP = "platform.redis.io";
    private static final String CRD_VERSION = "v1";
    private static final String CRD_PLURAL = "managedredis";
    private static final String CRD_KIND = "ManagedRedis";

    @Value("${platform.kubernetes.master-url:}")
    private String masterUrl;

    @Value("${platform.kubernetes.namespace:default}")
    private String defaultNamespace;

    @Value("${platform.kubernetes.kubeconfig:}")
    private String kubeconfigPath;

    private KubernetesClient client;

    private final CustomResourceDefinitionContext crdContext = new CustomResourceDefinitionContext.Builder()
            .withGroup(CRD_GROUP)
            .withVersion(CRD_VERSION)
            .withPlural(CRD_PLURAL)
            .withKind(CRD_KIND)
            .withScope("Namespaced")
            .build();

    @PostConstruct
    public void init() {
        ConfigBuilder configBuilder = new ConfigBuilder();

        if (masterUrl != null && !masterUrl.isBlank()) {
            configBuilder.withMasterUrl(masterUrl);
        }

        if (kubeconfigPath != null && !kubeconfigPath.isBlank()) {
            // Use explicit kubeconfig file
            Config config = Config.fromKubeconfig(null, kubeconfigPath, null);
            this.client = new KubernetesClientBuilder()
                    .withConfig(config)
                    .build();
        } else {
            // Auto-detect: in-cluster config or default kubeconfig
            this.client = new KubernetesClientBuilder()
                    .withConfig(configBuilder.build())
                    .build();
        }

        log.info("Kubernetes Operator Service initialized: masterUrl={}, namespace={}",
                client.getMasterUrl(), defaultNamespace);
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) {
            client.close();
        }
    }

    /**
     * Creates a ManagedRedis CRD on the target cluster.
     */
    public void createManagedRedis(RedisInstance instance) {
        String namespace = tenantNamespace(instance.getTenantId());

        // Ensure namespace exists
        ensureNamespace(namespace, instance.getTenantId());

        // Build the CRD as a GenericKubernetesResource
        GenericKubernetesResource crd = buildCrdResource(instance, namespace);

        log.info("Creating ManagedRedis CRD on cluster: name={}, namespace={}",
                instance.getId(), namespace);

        // Check if it already exists (idempotency)
        GenericKubernetesResource existing = client.genericKubernetesResources(crdContext)
                .inNamespace(namespace)
                .withName(instance.getId())
                .get();

        if (existing != null) {
            // Update existing resource
            client.genericKubernetesResources(crdContext)
                    .inNamespace(namespace)
                    .withName(instance.getId())
                    .patch(crd);
            log.info("ManagedRedis CRD updated: {}/{}", namespace, instance.getId());
        } else {
            // Create new resource
            client.genericKubernetesResources(crdContext)
                    .inNamespace(namespace)
                    .resource(crd)
                    .create();
            log.info("ManagedRedis CRD created: {}/{}", namespace, instance.getId());
        }
    }

    /**
     * Deletes the ManagedRedis CRD (operator handles cleanup of child resources).
     */
    public void deleteManagedRedis(RedisInstance instance) {
        String namespace = tenantNamespace(instance.getTenantId());
        log.info("Deleting ManagedRedis CRD: {}/{}", namespace, instance.getId());

        GenericKubernetesResource existing = client.genericKubernetesResources(crdContext)
                .inNamespace(namespace)
                .withName(instance.getId())
                .get();

        if (existing != null) {
            client.genericKubernetesResources(crdContext)
                    .inNamespace(namespace)
                    .withName(instance.getId())
                    .delete();
            log.info("ManagedRedis CRD deleted: {}/{}", namespace, instance.getId());
        } else {
            log.info("ManagedRedis CRD already absent: {}/{}", namespace, instance.getId());
        }
    }

    /**
     * Gets the current status reported by the operator.
     */
    public ManagedRedisStatus getStatus(RedisInstance instance) {
        String namespace = tenantNamespace(instance.getTenantId());

        GenericKubernetesResource resource = client.genericKubernetesResources(crdContext)
                .inNamespace(namespace)
                .withName(instance.getId())
                .get();

        if (resource == null) {
            log.warn("ManagedRedis CRD not found: {}/{}", namespace, instance.getId());
            return new ManagedRedisStatus("NotFound", 0, 0, 0, null);
        }

        // Extract status from the resource
        Map<String, Object> additionalProperties = resource.getAdditionalProperties();
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) additionalProperties.get("status");

        if (status == null) {
            return new ManagedRedisStatus("Pending", 0, instance.getShards(), 0, null);
        }

        String phase = (String) status.getOrDefault("phase", "Unknown");
        int readyShards = status.get("readyShards") != null ? ((Number) status.get("readyShards")).intValue() : 0;
        int totalShards = status.get("totalShards") != null ? ((Number) status.get("totalShards")).intValue() : instance.getShards();
        int totalReplicas = status.get("totalReplicas") != null ? ((Number) status.get("totalReplicas")).intValue() : 0;
        String endpoint = (String) status.get("endpoint");

        return new ManagedRedisStatus(phase, readyShards, totalShards, totalReplicas, endpoint);
    }

    /**
     * Ensures the tenant namespace exists with proper labels and resource quotas.
     */
    private void ensureNamespace(String namespace, String tenantId) {
        Namespace ns = client.namespaces().withName(namespace).get();

        if (ns == null) {
            log.info("Creating namespace: {}", namespace);

            Namespace newNs = new NamespaceBuilder()
                    .withNewMetadata()
                        .withName(namespace)
                        .withLabels(Map.of(
                                "platform.redis.io/tenant", tenantId,
                                "platform.redis.io/managed-by", "control-plane"
                        ))
                    .endMetadata()
                    .build();

            client.namespaces().resource(newNs).create();

            // Create NetworkPolicy to isolate tenant
            createNetworkPolicy(namespace, tenantId);

            log.info("Namespace created with isolation: {}", namespace);
        }
    }

    /**
     * Creates a default-deny NetworkPolicy for tenant isolation.
     */
    private void createNetworkPolicy(String namespace, String tenantId) {
        GenericKubernetesResource netpol = new GenericKubernetesResource();
        netpol.setApiVersion("networking.k8s.io/v1");
        netpol.setKind("NetworkPolicy");
        netpol.setMetadata(new ObjectMetaBuilder()
                .withName("tenant-isolation")
                .withNamespace(namespace)
                .withLabels(Map.of("platform.redis.io/tenant", tenantId))
                .build());

        Map<String, Object> spec = new HashMap<>();
        spec.put("podSelector", Map.of());
        spec.put("policyTypes", java.util.List.of("Ingress", "Egress"));
        spec.put("ingress", java.util.List.of(
                Map.of("from", java.util.List.of(
                        Map.of("namespaceSelector", Map.of(
                                "matchLabels", Map.of("platform.redis.io/tenant", tenantId)
                        ))
                ))
        ));
        spec.put("egress", java.util.List.of(
                Map.of("to", java.util.List.of(
                        Map.of("namespaceSelector", Map.of(
                                "matchLabels", Map.of("platform.redis.io/tenant", tenantId)
                        ))
                )),
                // Allow DNS
                Map.of("ports", java.util.List.of(
                        Map.of("protocol", "UDP", "port", 53),
                        Map.of("protocol", "TCP", "port", 53)
                ))
        ));
        netpol.setAdditionalProperty("spec", spec);

        try {
            client.resource(netpol).inNamespace(namespace).create();
        } catch (Exception e) {
            log.warn("Could not create NetworkPolicy in {}: {}", namespace, e.getMessage());
        }
    }

    /**
     * Builds the ManagedRedis GenericKubernetesResource.
     */
    private GenericKubernetesResource buildCrdResource(RedisInstance instance, String namespace) {
        GenericKubernetesResource crd = new GenericKubernetesResource();
        crd.setApiVersion(CRD_GROUP + "/" + CRD_VERSION);
        crd.setKind(CRD_KIND);
        crd.setMetadata(new ObjectMetaBuilder()
                .withName(instance.getId())
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "platform.redis.io/tenant", instance.getTenantId(),
                        "platform.redis.io/region", instance.getRegion(),
                        "platform.redis.io/managed-by", "control-plane"
                ))
                .build());

        Map<String, Object> spec = new HashMap<>();
        spec.put("shards", instance.getShards());
        spec.put("replicasPerShard", instance.getReplicasPerShard());
        spec.put("version", instance.getRedisVersion() != null ? instance.getRedisVersion() : "7.x");

        spec.put("persistence", Map.of(
                "enabled", instance.isPersistenceEnabled(),
                "storageClass", instance.getStorageClass() != null ? instance.getStorageClass() : "gp3",
                "size", instance.getStorageSize() != null ? instance.getStorageSize() : "10Gi"
        ));

        spec.put("highAvailability", Map.of(
                "enabled", instance.getReplicasPerShard() != null && instance.getReplicasPerShard() > 0,
                "minAvailable", instance.getShards() != null ? instance.getShards() : 1
        ));

        spec.put("resources", Map.of(
                "memory", instance.getMemory(),
                "cpu", "4"
        ));

        spec.put("security", Map.of(
                "tls", instance.isTlsEnabled(),
                "authSecret", instance.getId() + "-auth"
        ));

        spec.put("monitoring", Map.of(
                "enabled", true,
                "scrapeInterval", "15s"
        ));

        spec.put("network", Map.of(
                "access", instance.getNetworkAccess() != null ? instance.getNetworkAccess().name().toLowerCase() : "private"
        ));

        crd.setAdditionalProperty("spec", spec);
        return crd;
    }

    private String tenantNamespace(String tenantId) {
        return "tenant-" + tenantId;
    }

    /**
     * Status reported by the ManagedRedis operator.
     */
    public record ManagedRedisStatus(
            String phase,
            int readyShards,
            int totalShards,
            int totalReplicas,
            String endpoint
    ) {}
}
