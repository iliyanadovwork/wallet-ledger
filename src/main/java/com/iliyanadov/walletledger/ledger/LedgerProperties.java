package com.iliyanadov.walletledger.ledger;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Everything about the ledger that is a decision rather than a fact.
 *
 * <p>Defaults are applied here rather than only in {@code application.yml} so a profile that
 * overrides one value cannot accidentally blank the rest.
 */
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(
        String strategy,
        int optimisticMaxAttempts,
        Duration idempotencyTtl,
        Duration settlementInterval,
        int settlementWorkers) {

    public LedgerProperties {
        strategy = strategy == null ? "pessimistic" : strategy;
        optimisticMaxAttempts = optimisticMaxAttempts <= 0 ? 8 : optimisticMaxAttempts;
        idempotencyTtl = idempotencyTtl == null ? Duration.ofHours(24) : idempotencyTtl;
        settlementInterval = settlementInterval == null ? Duration.ofSeconds(1) : settlementInterval;
        settlementWorkers = settlementWorkers <= 0 ? 4 : settlementWorkers;
    }
}
