package io.platform.redis.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Platform Metrics: tracks control plane and data plane operational metrics.
 *
 * Control-plane metrics:
 *   - redis.create.requests.total
 *   - redis.create.successes.total
 *   - redis.create.failures.total
 *   - redis.create.duration.seconds
 *   - redis.upgrade.total
 *   - redis.rollback.total
 *   - redis.reconcile.cycles.total
 *   - redis.reconcile.errors.total
 *   - redis.instances.degraded (gauge)
 *
 * SLIs:
 *   - Provisioning success rate = successes / (successes + failures)
 *   - 99% of instances created within 10 minutes
 *   - 99.99% endpoint availability
 */
@Component
public class PlatformMetrics {

    private final Counter createRequests;
    private final Counter createSuccesses;
    private final Counter createFailures;
    private final Counter upgradeTotal;
    private final Counter rollbackTotal;
    private final Timer createDuration;

    public PlatformMetrics(MeterRegistry registry) {
        this.createRequests = Counter.builder("redis.create.requests.total")
            .description("Total Redis create requests")
            .register(registry);

        this.createSuccesses = Counter.builder("redis.create.successes.total")
            .description("Successful Redis provisions")
            .register(registry);

        this.createFailures = Counter.builder("redis.create.failures.total")
            .description("Failed Redis provisions")
            .register(registry);

        this.upgradeTotal = Counter.builder("redis.upgrade.total")
            .description("Total upgrade operations")
            .register(registry);

        this.rollbackTotal = Counter.builder("redis.rollback.total")
            .description("Total rollback operations")
            .register(registry);

        this.createDuration = Timer.builder("redis.create.duration.seconds")
            .description("Time to provision a Redis instance")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
    }

    public void recordCreateRequest() { createRequests.increment(); }
    public void recordCreateSuccess(Duration duration) {
        createSuccesses.increment();
        createDuration.record(duration);
    }
    public void recordCreateFailure() { createFailures.increment(); }
    public void recordUpgrade() { upgradeTotal.increment(); }
    public void recordRollback() { rollbackTotal.increment(); }
}
