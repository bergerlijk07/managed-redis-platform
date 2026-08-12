package io.platform.redis.service;

import io.platform.redis.domain.entity.Operation;
import io.platform.redis.domain.entity.RedisInstance;
import io.platform.redis.domain.enums.OperationStatus;
import io.platform.redis.domain.enums.OperationType;
import io.platform.redis.domain.enums.ResourceStatus;
import io.platform.redis.domain.enums.WorkflowPhase;
import io.platform.redis.repository.OperationRepository;
import io.platform.redis.repository.RedisInstanceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Reconciliation Controller: the heart of the control plane.
 *
 * This is the Kubernetes controller pattern applied at the platform level:
 *
 *   while (true) {
 *       desired = getDesiredState(resource)
 *       actual  = inspectActualState(resource)
 *
 *       if (actual != desired) {
 *           reconcile(desired, actual)
 *       }
 *
 *       sleep(interval)
 *   }
 *
 * The reconciler never stops. It continuously drives the system toward desired state.
 */
@Service
public class ReconciliationController {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationController.class);

    private final RedisInstanceRepository instanceRepository;
    private final OperationRepository operationRepository;
    private final WorkflowOrchestrator orchestrator;
    private final Counter reconcileCounter;
    private final Counter reconcileErrorCounter;

    public ReconciliationController(
            RedisInstanceRepository instanceRepository,
            OperationRepository operationRepository,
            WorkflowOrchestrator orchestrator,
            MeterRegistry meterRegistry) {
        this.instanceRepository = instanceRepository;
        this.operationRepository = operationRepository;
        this.orchestrator = orchestrator;
        this.reconcileCounter = meterRegistry.counter("redis.reconcile.cycles.total");
        this.reconcileErrorCounter = meterRegistry.counter("redis.reconcile.errors.total");
    }

    /**
     * Main reconciliation loop. Runs every N seconds.
     * Finds all instances with state drift and drives them toward desired state.
     */
    @Scheduled(fixedDelayString = "${platform.reconciler.interval-seconds:30}000")
    public void reconcile() {
        reconcileCounter.increment();

        List<RedisInstance> drifted = instanceRepository.findDrifted();

        if (!drifted.isEmpty()) {
            log.info("Reconciliation cycle: found {} drifted instances", drifted.size());
        }

        for (RedisInstance instance : drifted) {
            try {
                reconcileInstance(instance);
            } catch (Exception e) {
                reconcileErrorCounter.increment();
                log.error("Failed to reconcile instance {}: {}",
                    instance.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Reconcile a single instance by examining the gap between desired and actual.
     */
    private void reconcileInstance(RedisInstance instance) {
        log.debug("Reconciling: id={}, desired={}, actual={}",
            instance.getId(), instance.getDesiredStatus(), instance.getActualStatus());

        if (instance.needsProvisioning()) {
            driveProvisioningWorkflow(instance);
        } else if (instance.needsDeletion()) {
            driveDeletionWorkflow(instance);
        } else if (instance.isDegraded()) {
            healInstance(instance);
        } else if (instance.getActualStatus() == ResourceStatus.PROVISIONING) {
            advanceActiveWorkflow(instance);
        } else if (instance.getActualStatus() == ResourceStatus.FAILED) {
            evaluateRetry(instance);
        }
    }

    /**
     * Find the pending CREATE operation and advance it one phase.
     */
    private void driveProvisioningWorkflow(RedisInstance instance) {
        operationRepository.findFirstByResourceIdAndTypeAndStatusIn(
                instance.getId(),
                OperationType.CREATE,
                List.of(OperationStatus.PENDING, OperationStatus.RUNNING))
            .ifPresent(op -> {
                op.markRunning();
                orchestrator.advanceOperation(op);
            });
    }

    /**
     * Find the DELETE operation and advance it.
     */
    private void driveDeletionWorkflow(RedisInstance instance) {
        operationRepository.findFirstByResourceIdAndTypeAndStatusIn(
                instance.getId(),
                OperationType.DELETE,
                List.of(OperationStatus.PENDING, OperationStatus.RUNNING))
            .ifPresent(op -> {
                op.markRunning();
                if (op.getPhase() == WorkflowPhase.VALIDATING) {
                    op.setPhase(WorkflowPhase.DELETING);
                }
                orchestrator.advanceOperation(op);
            });
    }

    /**
     * Advance an already-running workflow (instance in PROVISIONING state).
     */
    private void advanceActiveWorkflow(RedisInstance instance) {
        operationRepository.findByResourceIdAndStatus(instance.getId(), OperationStatus.RUNNING)
            .stream()
            .findFirst()
            .ifPresent(orchestrator::advanceOperation);
    }

    /**
     * Attempt to heal a degraded instance.
     */
    private void healInstance(RedisInstance instance) {
        log.info("Healing degraded instance: {}", instance.getId());
        // In production:
        // 1. Check which component is degraded (pod, replication, network)
        // 2. Take corrective action
        // 3. May involve restarting pods, promoting replicas, etc.
    }

    /**
     * Evaluate whether a failed instance should be retried.
     */
    private void evaluateRetry(RedisInstance instance) {
        log.info("Evaluating retry for failed instance: {}", instance.getId());
        // In production:
        // 1. Check failure reason (transient vs permanent)
        // 2. Check retry count
        // 3. If eligible: reset to REQUESTED, create new operation
        // 4. If not: leave as FAILED, alert operations team
    }
}
