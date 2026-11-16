package com.iliyanadov.walletledger.ledger;

import com.iliyanadov.walletledger.account.AccountService;
import com.iliyanadov.walletledger.support.Concurrently;
import com.iliyanadov.walletledger.support.IntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same workload that breaks the unguarded ledger, run against each of the three that work.
 *
 * <p>Same threads, same number of transfers, same two accounts, same starting gate. The only
 * thing that changes is which implementation is on the other end, which is what makes this a
 * comparison rather than three separate tests that happen to pass.
 *
 * <p>Two things are asserted and the second is the stricter one. The total across both accounts
 * is unchanged, and the payer's balance has gone down by exactly the amount the successful
 * transfers moved. A ledger can conserve the total and still be wrong about which transfers
 * happened, so conservation on its own is not enough.
 *
 * <p>Failures are allowed here and the optimistic strategy produces them. Running out of retry
 * attempts is a refusal, the transfer did not happen, and the balances have to agree that it did
 * not. What is not allowed is a transfer that reports success and moves nothing, or one that
 * reports failure and moves something.
 */
class ConcurrentTransferConservationIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentTransferConservationIT.class);

    private static final long OPENING_BALANCE = 1_000_000L;
    private static final long TRANSFER_AMOUNT = 100L;
    private static final int THREADS = 12;
    private static final int TRANSFERS_PER_THREAD = 40;

    @Autowired
    private SynchronizedLedgerService synchronised;

    @Autowired
    private OptimisticLedgerService optimistic;

    @Autowired
    private PessimisticLedgerService pessimistic;

    @Autowired
    private AccountService accounts;

    @Autowired
    private MeterRegistry meters;

    @ParameterizedTest(name = "the {0} ledger conserves money under concurrent transfers")
    @DisplayName("each of the three strategies survives the workload that breaks the unguarded one")
    @ValueSource(strings = {"synchronized", "optimistic", "pessimistic"})
    void concurrentTransfersConserveMoney(String strategy) {
        LedgerService ledger = ledgerNamed(strategy);
        UUID payer = accounts.open(OPENING_BALANCE).id();
        UUID payee = accounts.open(OPENING_BALANCE).id();
        double retriesBefore = optimisticRetries();

        long startedAt = System.nanoTime();
        Concurrently.Outcome outcome = Concurrently.run(THREADS, TRANSFERS_PER_THREAD,
                () -> ledger.transfer(payer, payee, TRANSFER_AMOUNT));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        long payerBalance = accounts.get(payer).available();
        long payeeBalance = accounts.get(payee).available();
        long moved = outcome.succeeded() * TRANSFER_AMOUNT;

        log.info("{} ledger: {} of {} transfers succeeded in {} ms, {} optimistic retries",
                strategy, outcome.succeeded(), outcome.attempted(), elapsedMillis,
                (long) (optimisticRetries() - retriesBefore));

        assertThat(outcome.succeeded())
                .as("a ledger that refused every transfer would satisfy everything below")
                .isGreaterThan(0);

        assertThat(payerBalance + payeeBalance)
                .as("the total across both accounts")
                .isEqualTo(OPENING_BALANCE * 2);

        assertThat(payerBalance)
                .as("the payer is down by exactly what the successful transfers moved")
                .isEqualTo(OPENING_BALANCE - moved);

        assertThat(payeeBalance)
                .as("and the payee is up by the same")
                .isEqualTo(OPENING_BALANCE + moved);

        assertThat(outcome.failures())
                .as("the only acceptable failure here is running out of retry attempts")
                .allMatch(failure -> failure instanceof ContendedAccountException);
    }

    private LedgerService ledgerNamed(String strategy) {
        return switch (strategy) {
            case "synchronized" -> synchronised;
            case "optimistic" -> optimistic;
            case "pessimistic" -> pessimistic;
            default -> throw new IllegalArgumentException("No ledger called " + strategy);
        };
    }

    private double optimisticRetries() {
        return meters.find("ledger.optimistic.retry").counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }
}
