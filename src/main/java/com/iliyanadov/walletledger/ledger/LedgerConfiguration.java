package com.iliyanadov.walletledger.ledger;

import com.iliyanadov.walletledger.account.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * All four implementations are built, and {@code ledger.strategy} decides which one the
 * application actually serves with. The tests need all four regardless of that setting, because
 * a test that only ever exercises the configured one would not be comparing anything.
 */
@Configuration
public class LedgerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LedgerConfiguration.class);

    @Bean
    UnguardedLedgerService unguardedLedgerService(AccountRepository accounts,
                                                  TransactionTemplate transactions,
                                                  JdbcTemplate jdbc,
                                                  LedgerMetrics metrics) {
        return new UnguardedLedgerService(accounts, transactions, jdbc, metrics);
    }

    @Bean
    SynchronizedLedgerService synchronizedLedgerService(AccountRepository accounts,
                                                        TransactionTemplate transactions,
                                                        LedgerMetrics metrics) {
        return new SynchronizedLedgerService(accounts, transactions, metrics);
    }

    @Bean
    OptimisticLedgerService optimisticLedgerService(AccountRepository accounts,
                                                    TransactionTemplate transactions,
                                                    LedgerMetrics metrics,
                                                    LedgerProperties properties) {
        return new OptimisticLedgerService(accounts, transactions, metrics,
                properties.optimisticMaxAttempts());
    }

    @Bean
    PessimisticLedgerService pessimisticLedgerService(AccountRepository accounts,
                                                      TransactionTemplate transactions,
                                                      LedgerMetrics metrics) {
        return new PessimisticLedgerService(accounts, transactions, metrics);
    }

    @Bean
    @Primary
    LedgerService activeLedgerService(LedgerProperties properties,
                                      UnguardedLedgerService unguarded,
                                      SynchronizedLedgerService synchronised,
                                      OptimisticLedgerService optimistic,
                                      PessimisticLedgerService pessimistic) {
        LedgerService chosen = switch (properties.strategy()) {
            case "unguarded" -> unguarded;
            case "synchronized" -> synchronised;
            case "optimistic" -> optimistic;
            case "pessimistic" -> pessimistic;
            default -> throw new IllegalArgumentException(
                    "ledger.strategy must be one of unguarded, synchronized, optimistic or "
                            + "pessimistic, got " + properties.strategy());
        };

        if (chosen == unguarded) {
            // Selectable so the race can be watched through the API rather than only in a test.
            // It loses money under concurrent load and it is meant to.
            log.warn("Serving with the unguarded ledger. Concurrent writes to one account will "
                    + "lose updates. See the README.");
        } else {
            log.info("Serving with the {} ledger", chosen.strategy());
        }
        return chosen;
    }
}
