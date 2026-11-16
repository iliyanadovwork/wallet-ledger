package com.iliyanadov.walletledger.ledger;

import com.iliyanadov.walletledger.account.AccountRepository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Correct, and only inside one JVM.
 *
 * <p>One monitor covers the whole ledger, so exactly one unit of work runs at a time and the race
 * has nowhere to happen.
 *
 * <p>Two costs, and both are worth saying out loud before anyone reaches for this.
 *
 * <p>The first is that unrelated accounts queue behind each other. A deposit into one account
 * waits for a transfer between two others that it will never touch. A monitor per account would
 * fix the throughput and reintroduce the deadlock in {@code transfer}, because two threads would
 * then hold one monitor each and wait for the other, so it would need the same id ordering the
 * pessimistic strategy uses. A single monitor is slow and has nothing to get wrong.
 *
 * <p>The second is that it stops working the moment a second instance starts. A monitor is a fact
 * about one heap. Two processes against one database have two monitors and no shared answer, and
 * the failure that returns is the original race, at a rate nobody can reproduce locally because it
 * needs two instances to show up at all. Of the three strategies here this is the only one that
 * gets quietly less correct as the deployment grows.
 *
 * <p>The lock is taken outside the transaction and that is not a detail. The work is queued at
 * the database and only lands when the transaction commits, so a lock released before the commit
 * lets the next thread start reading while the previous write is still in flight. That window is
 * exactly the one this lock exists to close. It is the standard way of getting this wrong with
 * {@code @Transactional}, where {@code synchronized} inside the method body releases the monitor
 * at the closing brace and the proxy commits afterwards.
 */
class SynchronizedLedgerService extends TransactionalLedgerService {

    private final Object monitor = new Object();

    SynchronizedLedgerService(AccountRepository accounts,
                              TransactionTemplate transactions,
                              LedgerMetrics metrics) {
        super(accounts, transactions, metrics);
    }

    @Override
    public String strategy() {
        return "synchronized";
    }

    @Override
    protected void inUnitOfWork(String operation, Runnable work) {
        synchronized (monitor) {
            super.inUnitOfWork(operation, work);
        }
    }
}
