package io.platform.redis.domain.entity;

import io.platform.redis.domain.enums.OperationStatus;
import io.platform.redis.domain.enums.OperationType;
import io.platform.redis.domain.enums.WorkflowPhase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Operation tracks an async lifecycle operation (create, upgrade, delete, etc.)
 * Each operation is a durable record that can be resumed after crash.
 */
@Entity
@Table(name = "operations", indexes = {
    @Index(name = "idx_resource_id", columnList = "resourceId"),
    @Index(name = "idx_tenant_status", columnList = "tenantId, status")
})
public class Operation {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String resourceId;

    @Column(nullable = false, length = 64)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OperationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OperationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkflowPhase phase;

    @Column(length = 512)
    private String errorMessage;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    // === Constructors ===

    public Operation() {}

    public static Operation createNew(String id, String resourceId, String tenantId, OperationType type) {
        Operation op = new Operation();
        op.setId(id);
        op.setResourceId(resourceId);
        op.setTenantId(tenantId);
        op.setType(type);
        op.setStatus(OperationStatus.PENDING);
        op.setPhase(WorkflowPhase.VALIDATING);
        op.setCreatedAt(Instant.now());
        op.setUpdatedAt(Instant.now());
        return op;
    }

    // === Getters and Setters ===

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public OperationType getType() { return type; }
    public void setType(OperationType type) { this.type = type; }

    public OperationStatus getStatus() { return status; }
    public void setStatus(OperationStatus status) { this.status = status; }

    public WorkflowPhase getPhase() { return phase; }
    public void setPhase(WorkflowPhase phase) { this.phase = phase; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    // === Domain Methods ===

    public boolean isActive() {
        return status == OperationStatus.PENDING || status == OperationStatus.RUNNING;
    }

    public void markRunning() {
        this.status = OperationStatus.RUNNING;
        this.updatedAt = Instant.now();
    }

    public void markSucceeded() {
        this.status = OperationStatus.SUCCEEDED;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status = OperationStatus.FAILED;
        this.errorMessage = error;
        this.updatedAt = Instant.now();
    }

    public void advancePhase(WorkflowPhase nextPhase) {
        this.phase = nextPhase;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
