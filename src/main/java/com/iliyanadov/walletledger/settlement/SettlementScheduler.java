package com.iliyanadov.walletledger.settlement;

import com.iliyanadov.walletledger.ledger.LedgerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Looks for accounts with settlements waiting and hands each one to a worker.
 *
 * <p>The ticks themselves cannot overlap. Spring runs scheduled methods on one thread unless it is
 * told otherwise, so a tick that takes longer than the interval just delays the next one. The
 * overlap appears the moment the work is handed off, which is what this does: the tick returns
 * immediately and the workers carry on, so a slow account is still settling when the next tick
 * finds it pending again and submits it a second time. {@link SettlementRunner} is what makes the
 * second submission a no-op instead of a second payment.
 *
 * <p>The pool is small and fixed. Every worker holds a database connection while it runs, so the
 * real ceiling is the connection pool, and a larger thread pool would not settle anything faster.
 * It would move the queue somewhere harder to see and make the connection pool the thing that
 * times out. Virtual threads would have the same problem for the same reason.
 *
 * <p>The health signal here covers settlement as a whole rather than each account. A single
 * account that cannot settle marks the service as failing. Reporting it per account needs one
 * entry per account and a decision about how long to keep them, which is more than this service
 * has a use for.
 */
@Component
public class SettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementScheduler.class);

    private final SettlementInstructionRepository instructions;
    private final SettlementRunner runner;
    private final SettlementJob job;
    private final SettlementHealth health;
    private final MeterRegistry meters;
    private final ExecutorService workers;

    public SettlementScheduler(SettlementInstructionRepository instructions,
                               SettlementRunner runner,
                               SettlementJob job,
                               SettlementHealth health,
                               MeterRegistry meters,
                               LedgerProperties properties) {
        this.instructions = instructions;
        this.runner = runner;
        this.job = job;
        this.health = health;
        this.meters = meters;
        this.workers = Executors.newFixedThreadPool(properties.settlementWorkers(), namedThreads());
    }

    @Scheduled(fixedDelayString = "${ledger.settlement-interval:PT1S}")
    public void tick() {
        List<UUID> accounts;
        try {
            accounts = instructions.accountsWithPendingWork();
        } catch (RuntimeException failure) {
            health.recordFailure();
            log.warn("Could not read pending settlements", failure);
            return;
        }

        for (UUID accountId : accounts) {
            workers.submit(() -> settle(accountId));
        }
    }

    private void settle(UUID accountId) {
        try {
            AtomicReference<SettlementJob.Outcome> outcome = new AtomicReference<>();
            boolean ran = runner.runFor(accountId, () -> outcome.set(job.settleAccount(accountId)));

            if (!ran) {
                meters.counter("settlement.skipped.already.running").increment();
                return;
            }

            SettlementJob.Outcome result = outcome.get();
            if (result.stalled()) {
                health.recordFailure();
                meters.counter("settlement.stalled").increment();
                log.warn("Settlement stopped on instruction {} for account {}. Nothing queued "
                                + "behind it will be applied until this one clears.",
                        result.stalledOn(), accountId, result.failure());
            } else {
                health.recordSuccess();
            }
        } catch (RuntimeException failure) {
            health.recordFailure();
            log.warn("Settlement failed for account {}", accountId, failure);
        }
    }

    @PreDestroy
    void stopWorkers() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "settlement-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
