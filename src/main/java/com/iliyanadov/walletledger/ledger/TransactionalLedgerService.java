package com.iliyanadov.walletledger.ledger;

import com.iliyanadov.walletledger.account.Account;
import com.iliyanadov.walletledger.account.AccountNotFoundException;
import com.iliyanadov.walletledger.account.AccountRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * The arithmetic, once, for the three implementations that are actually correct.
 *
 * <p>Subclasses change one of two things and nothing else. {@link #acquire} says how a row is
 * read inside the transaction, and {@link #inUnitOfWork} says what wraps the transaction. That is
 * the entire difference between synchronising, versioning and locking the row, which is the point
 * worth noticing: the three approaches are not three designs, they are three answers to one
 * question about who is allowed to be in the account at the same time.
 *
 * <p>The transaction is opened with {@link TransactionTemplate} rather than {@code @Transactional}
 * so the boundary is a pair of visible braces. It matters here. Two of the three subclasses do
 * something either side of that boundary and getting the side wrong silently removes the
 * protection.
 *
 * <p>Nothing calls {@code save}. The accounts are read inside the transaction, so they are managed
 * and Hibernate writes the changes when the transaction commits.
 */
abstract class TransactionalLedgerService implements LedgerService {

    protected final AccountRepository accounts;
    protected final TransactionTemplate transactions;
    private final LedgerMetrics metrics;

    protected TransactionalLedgerService(AccountRepository accounts,
                                         TransactionTemplate transactions,
                                         LedgerMetrics metrics) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    /** Read one account. Inside a transaction, so the returned entity is managed. */
    protected Account acquire(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    /**
     * Run the work in a transaction.
     *
     * <p>Subclasses that override this may run {@code work} more than once, so a unit of work has
     * to read everything it touches through {@link #acquire} each time it runs. Carrying an
     * account across attempts would retry with the balances that already lost.
     */
    protected void inUnitOfWork(String operation, Runnable work) {
        transactions.executeWithoutResult(status -> work.run());
    }

    @Override
    public void deposit(UUID accountId, long amount) {
        measured("deposit", () -> acquire(accountId).credit(amount));
    }

    @Override
    public void reserve(UUID accountId, long amount) {
        measured("reserve", () -> acquire(accountId).reserve(amount));
    }

    @Override
    public void release(UUID accountId, long amount) {
        measured("release", () -> acquire(accountId).release(amount));
    }

    @Override
    public void settle(UUID accountId, long released, long settled) {
        measured("settle", () -> acquire(accountId).settle(released, settled));
    }

    @Override
    public void transfer(UUID fromId, UUID toId, long amount) {
        if (fromId.equals(toId)) {
            throw new IllegalArgumentException("A transfer needs two different accounts");
        }
        measured("transfer", () -> {
            // Acquire in account id order rather than payer-then-payee. Under the pessimistic
            // strategy these are row locks, and two transfers running in opposite directions
            // between the same pair would each hold the row the other one is waiting for.
            // Ordering the pair by id means every caller in the system queues for the same row
            // first, so the cycle cannot form. Nothing here depends on which account is paying.
            boolean payerFirst = fromId.compareTo(toId) < 0;
            Account first = acquire(payerFirst ? fromId : toId);
            Account second = acquire(payerFirst ? toId : fromId);

            Account payer = payerFirst ? first : second;
            Account payee = payerFirst ? second : first;

            payer.debit(amount);
            payee.credit(amount);
        });
    }

    private void measured(String operation, Runnable work) {
        long startedAt = System.nanoTime();
        String outcome = "ok";
        try {
            inUnitOfWork(operation, work);
        } catch (RuntimeException failure) {
            outcome = failure.getClass().getSimpleName();
            throw failure;
        } finally {
            metrics.recordOperation(strategy(), operation, outcome, System.nanoTime() - startedAt);
        }
    }
}
