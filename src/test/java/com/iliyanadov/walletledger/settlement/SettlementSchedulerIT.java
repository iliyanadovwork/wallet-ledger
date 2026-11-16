package com.iliyanadov.walletledger.settlement;

import com.iliyanadov.walletledger.account.AccountService;
import com.iliyanadov.walletledger.account.InsufficientFundsException;
import com.iliyanadov.walletledger.ledger.LedgerService;
import com.iliyanadov.walletledger.support.IntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Settlements going in one end and balances moving at the other.
 *
 * <p>Ticks are driven by hand rather than left to the scheduler's timer. The test then finishes
 * when the work is done instead of when a timer happens to fire, which is the difference between
 * a test that takes a known amount of time and one that is slow and fails occasionally on a busy
 * machine.
 */
class SettlementSchedulerIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @Autowired
    private SettlementInbox inbox;

    @Autowired
    private SettlementScheduler scheduler;

    @Autowired
    private SettlementInstructionRepository instructions;

    @Autowired
    private AccountService accounts;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private SettlementJob job;

    @Test
    @DisplayName("a queued settlement moves the balances once the job picks it up")
    void queuedSettlementsAreApplied() {
        UUID account = accounts.open(10_000).id();
        ledger.reserve(account, 4_000);
        UUID instructionId = inbox.enqueue(account, 4_000, 4_000);

        scheduler.tick();

        Awaitility.await().atMost(PATIENCE).untilAsserted(() -> {
            assertThat(applied(instructionId)).isTrue();
            assertThat(accounts.get(account).available()).isEqualTo(6_000);
            assertThat(accounts.get(account).locked()).isZero();
        });
    }

    @Test
    @DisplayName("settling the same account twice applies each instruction once")
    void settlingTheSameAccountTwiceMovesTheMoneyOnce() {
        UUID account = accounts.open(10_000).id();

        // Reserve twice what the instruction releases. With the two equal, a second application
        // would be refused by the account itself for having nothing left locked, and this would
        // pass whether or not anything stopped the instruction running twice. Leaving 3,000 still
        // promised means a second application would succeed, and the balances would show it.
        ledger.reserve(account, 6_000);
        UUID instructionId = inbox.enqueue(account, 3_000, 3_000);

        // Called directly rather than through the scheduler. A tick dispatches to a worker and
        // returns, so ticking five times does not reliably produce five runs, and a test that
        // depends on that is measuring how busy the machine is.
        assertThat(job.settleAccount(account).applied()).isEqualTo(1);
        assertThat(job.settleAccount(account).applied())
                .as("the instruction is already marked, so the second run has nothing to do")
                .isZero();

        assertThat(applied(instructionId)).isTrue();
        assertThat(accounts.get(account).available())
                .as("applied twice this would be 4,000")
                .isEqualTo(7_000);
        assertThat(accounts.get(account).locked()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("a settlement larger than the amount reserved against it leaves the account underwater")
    void aLossBiggerThanTheReservationIsStillApplied() {
        UUID account = accounts.open(10_000).id();
        ledger.reserve(account, 8_000);

        // 4,000 of the 8,000 was reserved against a position that closed at a 7,000 loss.
        UUID instructionId = inbox.enqueue(account, 4_000, 7_000);

        scheduler.tick();

        Awaitility.await().atMost(PATIENCE).untilAsserted(() -> {
            assertThat(applied(instructionId)).isTrue();
            assertThat(accounts.get(account).available()).isEqualTo(3_000);
            assertThat(accounts.get(account).locked()).isEqualTo(4_000);
            assertThat(accounts.get(account).spendable()).isNegative();
        });
    }

    @Test
    @DisplayName("a settlement that cannot apply is refused when it is queued, not when it runs")
    void anInstructionThatCouldNeverApplyIsRefusedAtTheDoor() {
        UUID account = accounts.open(10_000).id();
        ledger.reserve(account, 1_000);

        // Nothing like this much is promised, so this instruction could not apply on any tick.
        // Accepting it would wedge the account's queue behind something that fails forever.
        assertThatExceptionOfType(InsufficientFundsException.class)
                .isThrownBy(() -> inbox.enqueue(account, 5_000, 5_000));

        assertThat(instructions.accountsWithPendingWork()).doesNotContain(account);
    }

    @Test
    @DisplayName("an instruction that stops the run says which one it was, and holds the queue behind it")
    void aStalledInstructionIsNamedAndStopsTheAccount() {
        UUID account = accounts.open(10_000).id();
        ledger.reserve(account, 4_000);

        UUID first = inbox.enqueue(account, 4_000, 4_000);
        UUID second = inbox.enqueue(account, 4_000, 4_000);

        // The first one uses up the whole reservation, so the second cannot apply. Settlements
        // for one account are ordered, so the run stops rather than reaching past it.
        SettlementJob.Outcome outcome = job.settleAccount(account);

        assertThat(outcome.applied()).isEqualTo(1);
        assertThat(outcome.stalledOn())
                .as("the run names the instruction it stopped on, rather than failing anonymously")
                .isEqualTo(second);
        assertThat(outcome.failure()).isInstanceOf(InsufficientFundsException.class);

        assertThat(applied(first)).isTrue();
        assertThat(applied(second)).isFalse();
        assertThat(accounts.get(account).available()).isEqualTo(6_000);
    }

    private boolean applied(UUID instructionId) {
        return instructions.findById(instructionId).orElseThrow().settledAt() != null;
    }
}
