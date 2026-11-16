package com.iliyanadov.walletledger.ledger;

import com.iliyanadov.walletledger.account.AccountService;
import com.iliyanadov.walletledger.account.InsufficientFundsException;
import com.iliyanadov.walletledger.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * All four implementations, including the broken one, against the same rules on one thread.
 *
 * <p>This is here to make the point that the unguarded ledger is not sloppy code. It passes
 * everything in this file. It reads balances, checks them properly, refuses what it should refuse
 * and writes the right numbers. What it does not survive is a second thread, and no test on one
 * thread can tell you that.
 *
 * <p>Which is the uncomfortable half of the subject. A concurrency defect is invisible to the kind
 * of testing that catches most defects, so it ships, and it is found later by a customer whose
 * balance is wrong.
 */
class LedgerSemanticsIT extends IntegrationTest {

    @Autowired
    private UnguardedLedgerService unguarded;

    @Autowired
    private SynchronizedLedgerService synchronised;

    @Autowired
    private OptimisticLedgerService optimistic;

    @Autowired
    private PessimisticLedgerService pessimistic;

    @Autowired
    private AccountService accounts;

    @ParameterizedTest(name = "the {0} ledger")
    @DisplayName("every implementation follows the same rules when nothing is competing with it")
    @ValueSource(strings = {"unguarded", "synchronized", "optimistic", "pessimistic"})
    void allFourAgreeOnOneThread(String strategy) {
        LedgerService ledger = ledgerNamed(strategy);
        UUID account = accounts.open(1_000).id();

        ledger.deposit(account, 500);
        assertThat(accounts.get(account).available()).isEqualTo(1_500);

        ledger.reserve(account, 600);
        assertThat(accounts.get(account).locked()).isEqualTo(600);
        assertThat(accounts.get(account).spendable()).isEqualTo(900);

        ledger.release(account, 100);
        assertThat(accounts.get(account).locked()).isEqualTo(500);

        ledger.settle(account, 500, 500);
        assertThat(accounts.get(account).available()).isEqualTo(1_000);
        assertThat(accounts.get(account).locked()).isZero();

        UUID other = accounts.open(0).id();
        ledger.transfer(account, other, 400);
        assertThat(accounts.get(account).available()).isEqualTo(600);
        assertThat(accounts.get(other).available()).isEqualTo(400);
    }

    @ParameterizedTest(name = "the {0} ledger")
    @DisplayName("every implementation refuses the same things")
    @ValueSource(strings = {"unguarded", "synchronized", "optimistic", "pessimistic"})
    void allFourRefuseTheSameThings(String strategy) {
        LedgerService ledger = ledgerNamed(strategy);
        UUID account = accounts.open(1_000).id();
        UUID other = accounts.open(0).id();

        ledger.reserve(account, 900);

        assertThatExceptionOfType(InsufficientFundsException.class)
                .as("only 100 is spendable, even though 1,000 is in the account")
                .isThrownBy(() -> ledger.transfer(account, other, 200));

        assertThatExceptionOfType(InsufficientFundsException.class)
                .isThrownBy(() -> ledger.reserve(account, 200));

        assertThatExceptionOfType(InsufficientFundsException.class)
                .isThrownBy(() -> ledger.release(account, 1_000));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ledger.deposit(account, 0));

        assertThatIllegalArgumentException()
                .as("a transfer to the same account is a typo, not a transfer")
                .isThrownBy(() -> ledger.transfer(account, account, 100));
    }

    @ParameterizedTest(name = "the {0} ledger")
    @DisplayName("every implementation lets a settlement go underwater and then refuses to let money out")
    @ValueSource(strings = {"unguarded", "synchronized", "optimistic", "pessimistic"})
    void allFourAllowSettleToBreakTheInvariantAndNotTransfer(String strategy) {
        LedgerService ledger = ledgerNamed(strategy);
        UUID account = accounts.open(1_000).id();
        UUID other = accounts.open(0).id();

        ledger.reserve(account, 800);

        // 400 of the 800 was reserved against a position that closed at a 700 loss.
        ledger.settle(account, 400, 700);

        assertThat(accounts.get(account).available()).isEqualTo(300);
        assertThat(accounts.get(account).locked()).isEqualTo(400);
        assertThat(accounts.get(account).spendable()).isEqualTo(-100);

        assertThatExceptionOfType(InsufficientFundsException.class)
                .as("the shortfall does not get to leave the account")
                .isThrownBy(() -> ledger.transfer(account, other, 1));
    }

    @ParameterizedTest(name = "the {0} ledger")
    @DisplayName("every implementation lets money into an underwater account, even less than it is short")
    @ValueSource(strings = {"unguarded", "synchronized", "optimistic", "pessimistic"})
    void allFourLetAnUnderwaterAccountBePaid(String strategy) {
        LedgerService ledger = ledgerNamed(strategy);
        UUID account = accounts.open(1_000).id();
        UUID payer = accounts.open(1_000).id();

        ledger.reserve(account, 800);
        ledger.settle(account, 400, 700);
        assertThat(accounts.get(account).spendable()).isEqualTo(-100);

        // Half of what it is short. It has to be allowed, or the account can never be made whole.
        ledger.deposit(account, 50);
        assertThat(accounts.get(account).spendable()).isEqualTo(-50);

        // The receiving half of a transfer is the same movement, so it has to work too. If it
        // did not, the payer's side would roll back with it and a third party would be refused
        // for the state of an account they do not own.
        ledger.transfer(payer, account, 25);
        assertThat(accounts.get(account).available()).isEqualTo(375);
        assertThat(accounts.get(payer).available()).isEqualTo(975);

        // And releasing a promise closes the gap rather than widening it.
        ledger.release(account, 200);
        assertThat(accounts.get(account).locked()).isEqualTo(200);
        assertThat(accounts.get(account).spendable()).isEqualTo(175);
    }

    private LedgerService ledgerNamed(String strategy) {
        return switch (strategy) {
            case "unguarded" -> unguarded;
            case "synchronized" -> synchronised;
            case "optimistic" -> optimistic;
            case "pessimistic" -> pessimistic;
            default -> throw new IllegalArgumentException("No ledger called " + strategy);
        };
    }
}
