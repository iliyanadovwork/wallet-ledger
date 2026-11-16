package com.iliyanadov.walletledger.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * One account, holding two numbers.
 *
 * <p>{@code available} is everything the account owns. {@code locked} is the part of it that is
 * already promised to something else. What the holder can actually spend is the difference. Both
 * are minor units, so pence rather than pounds, and both are {@code long}. There is no decimal
 * type anywhere in this project and there is not supposed to be.
 *
 * <p>The invariant is {@code 0 <= locked <= available}. {@link #debit} and {@link #reserve}
 * leave it true or refuse. {@link #settle} is allowed to break it, on purpose, and the reason is
 * written on the method. {@link #credit} and {@link #release} cannot break it and are allowed to
 * run while it is broken, because they are how it gets repaired.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    private UUID id;

    @Column(nullable = false)
    private long available;

    @Column(nullable = false)
    private long locked;

    /**
     * Hibernate adds this to the WHERE clause of every UPDATE it issues for this entity, which is
     * what makes the optimistic strategy work. It costs nothing when the strategy is not in use.
     *
     * <p>Boxed rather than a primitive {@code int}, which is not a style choice. Ids here are
     * assigned rather than generated, so Spring Data cannot tell a new account from an existing
     * one by looking at the id, and it falls back to the version field. A primitive is never null,
     * so every {@code save} would be treated as an update and go through {@code merge}, which is a
     * SELECT for a row that is not there yet followed by the INSERT. Null means new.
     */
    @Version
    @Column(nullable = false)
    private Integer version;

    protected Account() {
        // Hibernate wants a no-arg constructor. Nothing else should use it.
    }

    private Account(UUID id, long openingBalance) {
        this.id = id;
        this.available = openingBalance;
        this.locked = 0;
    }

    public static Account open(UUID id, long openingBalance) {
        if (openingBalance < 0) {
            throw new IllegalArgumentException("An account cannot open with a negative balance");
        }
        return new Account(id, openingBalance);
    }

    public UUID id() {
        return id;
    }

    public long available() {
        return available;
    }

    public long locked() {
        return locked;
    }

    public Integer version() {
        return version;
    }

    /** What is left once the promised part is set aside. Never negative while the invariant holds. */
    public long spendable() {
        return available - locked;
    }

    /** Money arriving. A deposit and the receiving half of a transfer are the same movement. */
    public void credit(long amount) {
        requirePositive(amount);
        long shortfallBefore = shortfall();
        available = Math.addExact(available, amount);
        requireNoWorse(shortfallBefore, "credit");
    }

    /**
     * Money leaving, which is the paying half of a transfer.
     *
     * <p>This checks {@link #spendable()} rather than {@code available}, so an account cannot pay
     * away money it has already promised somewhere else.
     */
    public void debit(long amount) {
        requirePositive(amount);
        if (spendable() < amount) {
            throw new InsufficientFundsException(id, amount, spendable());
        }
        available -= amount;
        requireInvariant("debit");
    }

    /** Promise an amount to something. The money stays in the account and stops being spendable. */
    public void reserve(long amount) {
        requirePositive(amount);
        if (spendable() < amount) {
            throw new InsufficientFundsException(id, amount, spendable());
        }
        locked = Math.addExact(locked, amount);
        requireInvariant("reserve");
    }

    /** Drop a promise without acting on it. The money becomes spendable again. */
    public void release(long amount) {
        requirePositive(amount);
        if (locked < amount) {
            throw new InsufficientFundsException(id, amount, locked);
        }
        long shortfallBefore = shortfall();
        locked -= amount;
        requireNoWorse(shortfallBefore, "release");
    }

    /**
     * Act on a promise: drop {@code released} from the promised part and take {@code settled} out
     * of the account.
     *
     * <p>The two numbers are separate because they are genuinely allowed to differ. Reserve 500
     * against a position, lose 700 on it, and the settlement releases 500 while taking 700. If
     * another 500 is still promised elsewhere, the account now owes more than it holds, and that
     * is not a bug. It is what a margin call looks like, and a ledger that refuses to represent it
     * simply cannot settle a loss.
     *
     * <p>So this is the one method that does not assert the full invariant. It still refuses to
     * take either number below zero. A transfer in the same position is refused outright, because
     * a transfer is the holder choosing to move money and a settlement is the market telling them
     * what happened.
     *
     * <p>It has to be one step. Releasing and then debiting leaves a window in which the money is
     * spendable and the loss has not landed yet, and every race in this repository lives in exactly
     * that kind of window.
     */
    public void settle(long released, long settled) {
        requirePositive(released);
        requireNotNegative(settled);
        if (locked < released) {
            throw new InsufficientFundsException(id, released, locked);
        }
        if (available < settled) {
            throw new InsufficientFundsException(id, settled, available);
        }
        locked -= released;
        available -= settled;
        requireNotUnderwater("settle");
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive, got " + amount);
        }
    }

    private static void requireNotNegative(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Amount must not be negative, got " + amount);
        }
    }

    /** How far the account is past what it holds. Negative whenever the invariant holds. */
    private long shortfall() {
        return locked - available;
    }

    /**
     * For the operations that must leave {@code locked <= available}, which is everything that
     * takes money out or promises more of it.
     *
     * <p>Both of those check what is spendable before they touch anything, so this can only fire
     * if that check is wrong. It is an assertion about this class, not a refusal aimed at a
     * caller, which is why it throws {@link InvariantViolationException} and not
     * {@link InsufficientFundsException}.
     */
    private void requireInvariant(String operation) {
        requireNotUnderwater(operation);
        if (locked > available) {
            throw new InvariantViolationException(id, operation, available, locked);
        }
    }

    /**
     * For the operations that cannot break the invariant but might be asked to run on an account
     * where it is already broken.
     *
     * <p>Crediting raises available and releasing lowers locked, so each one closes the gap or
     * leaves it alone. Neither can open one. Testing them against {@code locked <= available}
     * instead would refuse them on an account that a settlement had already put underwater, and
     * that is the wrong answer twice over. Paying money into an account that owes more than it
     * holds is how the shortfall gets cured, so refusing a payment because the shortfall has not
     * been cured yet leaves the holder with no way to cure it. And refusing to release a promise
     * would strand collateral on the one account that needs it back.
     *
     * <p>So the rule for these two is that the gap must not grow. It never does, and checking
     * costs one subtraction.
     */
    private void requireNoWorse(long shortfallBefore, String operation) {
        requireNotUnderwater(operation);
        if (shortfall() > shortfallBefore) {
            throw new InvariantViolationException(id, operation, available, locked);
        }
    }

    private void requireNotUnderwater(String operation) {
        if (available < 0 || locked < 0) {
            throw new InvariantViolationException(id, operation, available, locked);
        }
    }

    @Override
    public String toString() {
        return "Account[id=%s, available=%d, locked=%d, version=%d]".formatted(id, available, locked, version);
    }
}
