package com.iliyanadov.walletledger.account;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The rules an account follows, with nothing else running. No database, no Spring, no threads.
 *
 * <p>Everything about concurrency is tested elsewhere. This is the arithmetic, and it has to be
 * right before the locking around it means anything.
 */
class AccountTest {

    private static Account accountWith(long available, long locked) {
        Account account = Account.open(UUID.randomUUID(), available);
        if (locked > 0) {
            account.reserve(locked);
        }
        return account;
    }

    @Nested
    @DisplayName("the five operations")
    class Operations {

        @Test
        void creditAddsToAvailableAndLeavesTheReservedPartAlone() {
            Account account = accountWith(1_000, 400);

            account.credit(250);

            assertThat(account.available()).isEqualTo(1_250);
            assertThat(account.locked()).isEqualTo(400);
            assertThat(account.spendable()).isEqualTo(850);
        }

        @Test
        void reserveMovesMoneyOutOfReachWithoutMovingItOut() {
            Account account = accountWith(1_000, 0);

            account.reserve(300);

            assertThat(account.available()).isEqualTo(1_000);
            assertThat(account.locked()).isEqualTo(300);
            assertThat(account.spendable()).isEqualTo(700);
        }

        @Test
        void releasePutsItBackWithinReach() {
            Account account = accountWith(1_000, 300);

            account.release(300);

            assertThat(account.locked()).isZero();
            assertThat(account.spendable()).isEqualTo(1_000);
        }

        @Test
        void settleDropsTheReservationAndTakesTheMoney() {
            Account account = accountWith(1_000, 300);

            account.settle(300, 300);

            assertThat(account.available()).isEqualTo(700);
            assertThat(account.locked()).isZero();
        }

        @Test
        void debitTakesMoneyOut() {
            Account account = accountWith(1_000, 0);

            account.debit(400);

            assertThat(account.available()).isEqualTo(600);
        }
    }

    @Nested
    @DisplayName("what an account refuses")
    class Refusals {

        @Test
        void debitCannotReachMoneyThatIsAlreadyPromisedElsewhere() {
            Account account = accountWith(1_000, 900);

            // There is 1,000 in the account, so a naive check on available would let this through.
            assertThatExceptionOfType(InsufficientFundsException.class)
                    .isThrownBy(() -> account.debit(200));

            assertThat(account.available()).isEqualTo(1_000);
        }

        @Test
        void reserveCannotPromiseTheSameMoneyTwice() {
            Account account = accountWith(1_000, 900);

            assertThatExceptionOfType(InsufficientFundsException.class)
                    .isThrownBy(() -> account.reserve(200));
        }

        @Test
        void releaseCannotGiveBackMoreThanWasPromised() {
            Account account = accountWith(1_000, 100);

            assertThatExceptionOfType(InsufficientFundsException.class)
                    .isThrownBy(() -> account.release(150));
        }

        @Test
        void settleCannotTakeMoreThanTheAccountHolds() {
            Account account = accountWith(500, 500);

            assertThatExceptionOfType(InsufficientFundsException.class)
                    .isThrownBy(() -> account.settle(500, 600));
        }

        @Test
        void amountsMustBePositive() {
            Account account = accountWith(1_000, 0);

            assertThatIllegalArgumentException().isThrownBy(() -> account.credit(0));
            assertThatIllegalArgumentException().isThrownBy(() -> account.debit(-1));
            assertThatIllegalArgumentException().isThrownBy(() -> account.reserve(0));
            assertThatIllegalArgumentException().isThrownBy(() -> account.release(-5));
            assertThatIllegalArgumentException().isThrownBy(() -> account.settle(0, 10));
        }

        @Test
        void anAccountCannotOpenOwingMoney() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Account.open(UUID.randomUUID(), -1));
        }

        @Test
        void aBalanceTooLargeForALongThrowsRatherThanWrappingRound() {
            Account account = accountWith(Long.MAX_VALUE - 10, 0);

            // Math.addExact is the reason this is an exception. Plain addition would silently
            // produce a large negative balance, which is a ledger quietly inventing a debt.
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> account.credit(100));
        }
    }

    @Nested
    @DisplayName("settle is allowed to break the invariant and transfer is not")
    class TheAsymmetry {

        @Test
        void aLossLargerThanTheAmountReservedAgainstItLeavesTheAccountUnderwater() {
            // 1,000 in the account. 800 of it promised to two positions, 400 each.
            Account account = accountWith(1_000, 800);

            // One of them closes at a 700 loss. The 400 reserved against it is released and 700
            // is taken out of the account.
            account.settle(400, 700);

            assertThat(account.available()).isEqualTo(300);
            assertThat(account.locked()).isEqualTo(400);

            // 400 is still promised to the other position and there is only 300 left to cover it.
            // This is a margin call, not a corrupted ledger, and a system that refuses to
            // represent it cannot settle a loss at all.
            assertThat(account.spendable()).isNegative();
        }

        @Test
        void anUnderwaterAccountCannotTransferMoneyOut() {
            Account account = accountWith(1_000, 800);
            account.settle(400, 700);

            // The holder does not get to walk away from the shortfall by moving what is left.
            assertThatExceptionOfType(InsufficientFundsException.class)
                    .isThrownBy(() -> account.debit(1));
        }

        @Test
        void anUnderwaterAccountTakesInLessThanItIsShortAndStaysUnderwater() {
            Account account = accountWith(1_000, 800);
            account.settle(400, 700);
            assertThat(account.spendable()).isEqualTo(-100);

            // 50 does not cover the 100 shortfall, and refusing it would be the wrong answer.
            // Paying in is how the shortfall gets cured, so an account that has to be whole
            // before it can be paid can never be made whole.
            account.credit(50);

            assertThat(account.available()).isEqualTo(350);
            assertThat(account.locked()).isEqualTo(400);
            assertThat(account.spendable()).isEqualTo(-50);
        }

        @Test
        void enoughMoneyInBringsAnUnderwaterAccountBack() {
            Account account = accountWith(1_000, 800);
            account.settle(400, 700);

            account.credit(500);

            assertThat(account.available()).isEqualTo(800);
            assertThat(account.spendable()).isEqualTo(400);
        }

        @Test
        void anUnderwaterAccountCanStillReleaseAPromise() {
            Account account = accountWith(1_000, 800);
            account.settle(400, 700);

            // Releasing lowers locked, so it closes the gap. Refusing it would strand collateral
            // on the one account that needs it back.
            account.release(200);

            assertThat(account.locked()).isEqualTo(200);
            assertThat(account.spendable()).isEqualTo(100);
        }

        @Test
        void settleStillRefusesToTakeEitherNumberBelowZero() {
            Account account = accountWith(1_000, 800);

            assertThatExceptionOfType(InsufficientFundsException.class)
                    .isThrownBy(() -> account.settle(900, 100));
        }
    }
}
