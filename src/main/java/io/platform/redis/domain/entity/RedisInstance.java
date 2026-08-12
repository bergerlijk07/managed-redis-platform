package io.platform.redis.domain.entity;

import io.platform.redis.domain.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

/**
 * RedisInstance represents the customer's desired state for a managed Redis deployment.
 * This is the core domain entity - it holds both what the customer wants (desired)
 * and what actually exists (actual).
 */
@Entity
@Table(name = "redis_instances", indexes = {
    @Index(name = "idx_tenant_name", columnList = "tenantId, name", unique = true),
    @Index(name = "idx_status", columnList = "actualStatus")
})
public class RedisInstance {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 64)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CloudProvider cloud;

    @Column(nullable = false, length = 32)
    private String region;

    @Column(nullable = false, length = 16)
    private String memory;

    @Column(length = 16)
    private String redisVersion;

    @Column(length = 64)
    private String clusterId;

    // Availability
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AvailabilityMode availabilityMode;

    // Persistence
    private boolean persistenceEnabled;

    // Network
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NetworkAccess networkAccess;

    // Security
    private boolean encryptionAtRest;
    private boolean tlsEnabled;

    // Resolved Topology (output of policy engine)
    private Integer shards;
    private Integer replicasPerShard;

    @Column(length = 64)
    private String instanceType;

    @Column(length = 32)
    private String storageClass;

    @Column(length = 16)
    private String storageSize;

    @Column(length = 256)
    private String availabilityZones; // comma-separated

    // State tracking - the heart of the control plane
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResourceStatus desiredStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResourceStatus actualStatus;

    @Column(length = 256)
    private String endpoint;

    // Metadata
    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    // === Constructors ===

    public RedisInstance() {}

    // === Getters and Setters ===

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public CloudProvider getCloud() { return cloud; }
    public void setCloud(CloudProvider cloud) { this.cloud = cloud; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getMemory() { return memory; }
    public void setMemory(String memory) { this.memory = memory; }

    public String getRedisVersion() { return redisVersion; }
    public void setRedisVersion(String redisVersion) { this.redisVersion = redisVersion; }

    public String getClusterId() { return clusterId; }
    public void setClusterId(String clusterId) { this.clusterId = clusterId; }

    public AvailabilityMode getAvailabilityMode() { return availabilityMode; }
    public void setAvailabilityMode(AvailabilityMode availabilityMode) { this.availabilityMode = availabilityMode; }

    public boolean isPersistenceEnabled() { return persistenceEnabled; }
    public void setPersistenceEnabled(boolean persistenceEnabled) { this.persistenceEnabled = persistenceEnabled; }

    public NetworkAccess getNetworkAccess() { return networkAccess; }
    public void setNetworkAccess(NetworkAccess networkAccess) { this.networkAccess = networkAccess; }

    public boolean isEncryptionAtRest() { return encryptionAtRest; }
    public void setEncryptionAtRest(boolean encryptionAtRest) { this.encryptionAtRest = encryptionAtRest; }

    public boolean isTlsEnabled() { return tlsEnabled; }
    public void setTlsEnabled(boolean tlsEnabled) { this.tlsEnabled = tlsEnabled; }

    public Integer getShards() { return shards; }
    public void setShards(Integer shards) { this.shards = shards; }

    public Integer getReplicasPerShard() { return replicasPerShard; }
    public void setReplicasPerShard(Integer replicasPerShard) { this.replicasPerShard = replicasPerShard; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }

    public String getStorageClass() { return storageClass; }
    public void setStorageClass(String storageClass) { this.storageClass = storageClass; }

    public String getStorageSize() { return storageSize; }
    public void setStorageSize(String storageSize) { this.storageSize = storageSize; }

    public String getAvailabilityZones() { return availabilityZones; }
    public void setAvailabilityZones(String availabilityZones) { this.availabilityZones = availabilityZones; }

    public List<String> getAvailabilityZonesList() {
        if (availabilityZones == null || availabilityZones.isBlank()) return List.of();
        return List.of(availabilityZones.split(","));
    }

    public void setAvailabilityZonesList(List<String> azs) {
        this.availabilityZones = String.join(",", azs);
    }

    public ResourceStatus getDesiredStatus() { return desiredStatus; }
    public void setDesiredStatus(ResourceStatus desiredStatus) { this.desiredStatus = desiredStatus; }

    public ResourceStatus getActualStatus() { return actualStatus; }
    public void setActualStatus(ResourceStatus actualStatus) { this.actualStatus = actualStatus; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    // === Domain Methods ===

    public boolean isStateDrifted() {
        return desiredStatus != actualStatus;
    }

    public boolean needsProvisioning() {
        return desiredStatus == ResourceStatus.READY && actualStatus == ResourceStatus.REQUESTED;
    }

    public boolean needsDeletion() {
        return desiredStatus == ResourceStatus.DELETED && actualStatus != ResourceStatus.DELETED;
    }

    public boolean isDegraded() {
        return actualStatus == ResourceStatus.DEGRADED;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
