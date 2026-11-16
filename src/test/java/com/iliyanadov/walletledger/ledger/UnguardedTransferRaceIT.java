package com.iliyanadov.walletledger.ledger;

import com.iliyanadov.walletledger.account.AccountService;
import com.iliyanadov.walletledger.support.Concurrently;
import com.iliyanadov.walletledger.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * This test proves the ledger is broken, and it passes when it succeeds in proving that.
 *
 * <p>It is here on purpose and it is the reason the rest of the repository exists. A test suite
 * that only shows the fixed version working is asking to be taken on trust about what was wrong
 * with the old one. This runs the old one and measures it destroying money.
 *
 * <p>The claim being tested is the smallest one available: a transfer moves money between two
 * accounts, so the total across both is the same afterwards as it was before. Nothing about
 * balances, nothing about who can afford what. Just that the sum does not change, which is a thing
 * moving money cannot do.
 *
 * <p>{@code UnguardedLedgerService} reads both balances, decides, and writes both balances. Under
 * one thread that is fine and the second test here shows it. Under several it is not, because the
 * numbers it writes at the bottom were read at the top and another thread has written in between.
 *
 * <p>The workload runs more than once if it has to. A race is a race and one round can get lucky,
 * so it is repeated until it produces a discrepancy, and the test fails if it never does. In
 * practice the first round is enough.
 */
class UnguardedTransferRaceIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(UnguardedTransferRaceIT.class);

    private static final long OPENING_BALANCE = 1_000_000L;
    private static final long TRANSFER_AMOUNT = 100L;
    private static final int THREADS = 12;
    private static final int TRANSFERS_PER_THREAD = 40;
    private static final int MAX_ROUNDS = 8;

    @Autowired
    private UnguardedLedgerService unguarded;

    @Autowired
    private AccountService accounts;

    @Test
    @DisplayName("concurrent transfers through the unguarded ledger change the total across both accounts")
    void concurrentTransfersDoNotConserveMoney() {
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            UUID payer = accounts.open(OPENING_BALANCE).id();
            UUID payee = accounts.open(OPENING_BALANCE).id();
            long expectedTotal = OPENING_BALANCE * 2;

            Concurrently.Outcome outcome = Concurrently.run(THREADS, TRANSFERS_PER_THREAD,
                    () -> unguarded.transfer(payer, payee, TRANSFER_AMOUNT));

            long actualTotal = totalAcross(payer, payee);
            long discrepancy = actualTotal - expectedTotal;

            if (discrepancy != 0) {
                log.info("Unguarded ledger, round {}: {} threads x {} transfers of {}. "
                                + "{} reported success, {} failed. "
                                + "Total before {}, total after {}, difference {}.",
                        round, THREADS, TRANSFERS_PER_THREAD, TRANSFER_AMOUNT,
                        outcome.succeeded(), outcome.failed(),
                        expectedTotal, actualTotal, discrepancy);

                assertThat(actualTotal)
                        .as("a transfer cannot change how much money exists, and this one did")
                        .isNotEqualTo(expectedTotal);
                return;
            }

            log.info("Unguarded ledger, round {}: the total held. Going again.", round);
        }

        fail("Ran %d rounds of %d concurrent transfers without losing a single update. "
                        + "That is not expected and it means this test has stopped testing anything.",
                MAX_ROUNDS, THREADS * TRANSFERS_PER_THREAD);
    }

    @Test
    @DisplayName("the same unguarded ledger is correct when only one thread uses it")
    void theSameCodeIsCorrectOnOneThread() {
        UUID payer = accounts.open(OPENING_BALANCE).id();
        UUID payee = accounts.open(OPENING_BALANCE).id();
        int transfers = THREADS * TRANSFERS_PER_THREAD;

        for (int i = 0; i < transfers; i++) {
            unguarded.transfer(payer, payee, TRANSFER_AMOUNT);
        }

        assertThat(totalAcross(payer, payee)).isEqualTo(OPENING_BALANCE * 2);
        assertThat(accounts.get(payer).available())
                .isEqualTo(OPENING_BALANCE - transfers * TRANSFER_AMOUNT);
    }

    private long totalAcross(UUID payer, UUID payee) {
        return accounts.get(payer).available() + accounts.get(payee).available();
    }
}
