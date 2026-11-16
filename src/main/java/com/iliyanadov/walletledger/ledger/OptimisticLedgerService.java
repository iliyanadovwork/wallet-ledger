package com.iliyanadov.walletledger.ledger;

import com.iliyanadov.walletledger.account.AccountRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/**
 * Correct across instances, by noticing afterwards rather than preventing beforehand.
 *
 * <p>Nothing is locked. Each account carries a version column, Hibernate puts the version it read
 * into the WHERE clause of the UPDATE, and an update that matches no row means somebody else wrote
 * first. The transaction rolls back and this class runs the whole unit of work again against the
 * balances that won.
 *
 * <p>The retry has to be outside the transaction, which is why it lives here and not inside
 * {@code transfer}. Once a transaction has failed its version check it is finished, and anything
 * attempted inside it fails too. Retrying means a new transaction and a fresh read, and that is
 * the reason the base class insists a unit of work re-reads everything it touches.
 *
 * <p>The cost is that it does the work twice, and it gets worse exactly where it hurts. Under real
 * contention on one hot account a large share of attempts are thrown away, throughput falls while
 * database load rises, and a caller can be starved by shorter transactions that keep beating it to
 * the commit. The retry budget is what stops that turning into an unbounded queue: after the last
 * attempt the caller gets {@link ContendedAccountException} and a 503, which is honest. Retrying
 * forever would turn a contention problem into an availability problem with no signal attached.
 *
 * <p>The backoff is randomised. Two callers that collide and then wait the same fixed pause
 * collide again, and keep colliding, because nothing has broken the symmetry between them.
 */
class OptimisticLedgerService extends TransactionalLedgerService {

    private static final long MIN_BACKOFF_MICROS = 100L;
    private static final long MAX_BACKOFF_MICROS = 20_000L;

    private final int maxAttempts;
    private final LedgerMetrics metrics;

    OptimisticLedgerService(AccountRepository accounts,
                            TransactionTemplate transactions,
                            LedgerMetrics metrics,
                            int maxAttempts) {
        super(accounts, transactions, metrics);
        this.metrics = metrics;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public String strategy() {
        return "optimistic";
    }

    @Override
    protected void inUnitOfWork(String operation, Runnable work) {
        for (int attempt = 1; ; attempt++) {
            try {
                super.inUnitOfWork(operation, work);
                return;
            } catch (OptimisticLockingFailureException lostTheRace) {
                if (attempt >= maxAttempts) {
                    throw new ContendedAccountException(operation, attempt, lostTheRace);
                }
                metrics.recordOptimisticRetry(operation);
                backOff(attempt);
            }
        }
    }

    private static void backOff(int attempt) {
        long ceiling = Math.min(MIN_BACKOFF_MICROS << Math.min(attempt, 6), MAX_BACKOFF_MICROS);
        long micros = ThreadLocalRandom.current().nextLong(MIN_BACKOFF_MICROS, ceiling + 1);
        LockSupport.parkNanos(micros * 1_000L);
    }
}
