package com.iliyanadov.walletledger.ledger;

import com.iliyanadov.walletledger.account.Account;
import com.iliyanadov.walletledger.account.AccountNotFoundException;
import com.iliyanadov.walletledger.account.AccountRepository;
import com.iliyanadov.walletledger.account.InsufficientFundsException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * The version that does not work, kept because the test that breaks it is the point of the
 * repository.
 *
 * <p>It is the obvious first implementation and there is nothing strange about it. Read the
 * balances, decide whether the caller can afford it, write the new numbers back. Single threaded
 * it is correct, and {@code LedgerSemanticsIT} runs the same suite against it as against the
 * other three and it passes.
 *
 * <p>What it gets wrong is the gap. The decision is made against balances read at the top and
 * acted on at the bottom, so between those two moments another caller can read the same balances,
 * decide the same thing and write first. Both then write an absolute number computed from a
 * balance that is no longer true, and the second write erases the first. No error is raised
 * anywhere, because from either caller's point of view nothing went wrong. The money is just gone,
 * or there is more of it than there was.
 *
 * <p>Two details keep the gap open on purpose. The reads are in their own transactions, so nothing
 * is held. And the write goes through JDBC with the new balances spelled out, which skips both the
 * version column on {@link Account} and the checks inside it. Read through the entity and write
 * through the entity and Hibernate would catch this by itself, which is the whole argument for the
 * optimistic strategy.
 *
 * <p>{@code UnguardedTransferRaceIT} fires concurrent transfers at this and asserts that the total
 * across the two accounts changes, which is a thing a transfer cannot do.
 */
class UnguardedLedgerService implements LedgerService {

    private final AccountRepository accounts;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final LedgerMetrics metrics;

    UnguardedLedgerService(AccountRepository accounts,
                           TransactionTemplate transactions,
                           JdbcTemplate jdbc,
                           LedgerMetrics metrics) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Override
    public String strategy() {
        return "unguarded";
    }

    @Override
    public void deposit(UUID accountId, long amount) {
        measured("deposit", () -> {
            Balances now = read(accountId);
            requirePositive(amount);
            write(accountId, Math.addExact(now.available(), amount), now.locked());
        });
    }

    @Override
    public void reserve(UUID accountId, long amount) {
        measured("reserve", () -> {
            Balances now = read(accountId);
            requirePositive(amount);
            if (now.spendable() < amount) {
                throw new InsufficientFundsException(accountId, amount, now.spendable());
            }
            write(accountId, now.available(), Math.addExact(now.locked(), amount));
        });
    }

    @Override
    public void release(UUID accountId, long amount) {
        measured("release", () -> {
            Balances now = read(accountId);
            requirePositive(amount);
            if (now.locked() < amount) {
                throw new InsufficientFundsException(accountId, amount, now.locked());
            }
            write(accountId, now.available(), now.locked() - amount);
        });
    }

    @Override
    public void settle(UUID accountId, long released, long settled) {
        measured("settle", () -> {
            Balances now = read(accountId);
            requirePositive(released);
            if (settled < 0) {
                throw new IllegalArgumentException("Amount must not be negative, got " + settled);
            }
            if (now.locked() < released) {
                throw new InsufficientFundsException(accountId, released, now.locked());
            }
            if (now.available() < settled) {
                throw new InsufficientFundsException(accountId, settled, now.available());
            }
            write(accountId, now.available() - settled, now.locked() - released);
        });
    }

    @Override
    public void transfer(UUID fromId, UUID toId, long amount) {
        if (fromId.equals(toId)) {
            throw new IllegalArgumentException("A transfer needs two different accounts");
        }
        measured("transfer", () -> {
            Balances payer = read(fromId);
            Balances payee = read(toId);

            requirePositive(amount);
            if (payer.spendable() < amount) {
                throw new InsufficientFundsException(fromId, amount, payer.spendable());
            }

            // Both balances were read before this line and both are written after it. Everything
            // that goes wrong with this class goes wrong in here.
            transactions.executeWithoutResult(status -> {
                write(fromId, payer.available() - amount, payer.locked());
                write(toId, Math.addExact(payee.available(), amount), payee.locked());
            });
        });
    }

    private Balances read(UUID accountId) {
        return transactions.execute(status -> {
            Account account = accounts.findById(accountId)
                    .orElseThrow(() -> new AccountNotFoundException(accountId));
            return new Balances(account.available(), account.locked());
        });
    }

    private void write(UUID accountId, long available, long locked) {
        jdbc.update("update account set available = ?, locked = ? where id = ?",
                available, locked, accountId);
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive, got " + amount);
        }
    }

    private void measured(String operation, Runnable work) {
        long startedAt = System.nanoTime();
        String outcome = "ok";
        try {
            work.run();
        } catch (RuntimeException failure) {
            outcome = failure.getClass().getSimpleName();
            throw failure;
        } finally {
            metrics.recordOperation(strategy(), operation, outcome, System.nanoTime() - startedAt);
        }
    }

    private record Balances(long available, long locked) {
        long spendable() {
            return available - locked;
        }
    }
}
