package com.iliyanadov.walletledger.ledger;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Counters for the ledger.
 *
 * <p>The retry counter is the one worth having. The cost of optimistic locking is that it does the
 * work twice under contention, and a number nobody publishes is a cost nobody notices until the
 * latency graph moves for reasons that look unrelated.
 */
@Component
public class LedgerMetrics {

    private final MeterRegistry registry;

    public LedgerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordOperation(String strategy, String operation, String outcome, long nanos) {
        Timer.builder("ledger.operation")
                .tag("strategy", strategy)
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordOptimisticRetry(String operation) {
        registry.counter("ledger.optimistic.retry", "operation", operation).increment();
    }
}
