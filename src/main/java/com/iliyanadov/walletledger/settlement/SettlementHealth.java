package com.iliyanadov.walletledger.settlement;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Remembers when settlement last worked and when it started not working.
 *
 * <p>A boolean says broken. A timestamp says broken for how long, and the difference between
 * those two is the difference between an operator who can tell a blip from an outage and one who
 * cannot. Failing for four seconds during a database failover and failing for forty minutes
 * because a queue is wedged produce the same boolean.
 *
 * <p>{@code failingSince} is set on the first failure and left alone by the ones after it, so it
 * stays the moment the trouble started rather than sliding forward to the most recent attempt. A
 * field that updates on every failure reads as "it just broke" no matter how long it has been
 * broken for.
 */
@Component
public class SettlementHealth {

    private final Clock clock;
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    private final AtomicReference<Instant> failingSince = new AtomicReference<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    public SettlementHealth(Clock clock) {
        this.clock = clock;
    }

    public void recordSuccess() {
        lastSuccess.set(clock.instant());
        failingSince.set(null);
        consecutiveFailures.set(0);
    }

    public void recordFailure() {
        failingSince.compareAndSet(null, clock.instant());
        consecutiveFailures.incrementAndGet();
    }

    public Optional<Instant> lastSuccess() {
        return Optional.ofNullable(lastSuccess.get());
    }

    public Optional<Instant> failingSince() {
        return Optional.ofNullable(failingSince.get());
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }
}
