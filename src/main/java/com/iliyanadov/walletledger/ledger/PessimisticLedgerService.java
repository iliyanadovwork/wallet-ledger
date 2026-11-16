package com.iliyanadov.walletledger.ledger;

import com.iliyanadov.walletledger.account.Account;
import com.iliyanadov.walletledger.account.AccountNotFoundException;
import com.iliyanadov.walletledger.account.AccountRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Correct across instances, by making everybody else wait.
 *
 * <p>Every read takes a row lock, so the row is held until the transaction ends and a second
 * caller asking for the same account blocks in the database rather than reading a number that is
 * about to change. The correctness argument is the short one of the three: there is no window,
 * because the read and the write are inside the same lock.
 *
 * <p>The lock is {@code FOR NO KEY UPDATE} rather than {@code FOR UPDATE}, which is what
 * Hibernate's Postgres dialect emits for {@code PESSIMISTIC_WRITE} and is the better of the two
 * to be holding. It excludes other writers on the row, which is the whole job, and leaves
 * {@code FOR KEY SHARE} alone, which is what Postgres takes when something inserts a row
 * referencing this account. Under {@code FOR UPDATE} every settlement queued against an account
 * would wait for any transfer in progress, and gain nothing by waiting.
 * {@code PessimisticLockScopeIT} has both halves of that.
 *
 * <p>This is the default. It is the only one of the three that is both correct with several
 * instances running and predictable under load, and predictable is usually worth more than fast.
 *
 * <p>The cost is that a transaction stays open for as long as the lock is held, so the slowest
 * thing inside that transaction sets the rate for everyone queued behind it. Nothing slow belongs
 * in there: no HTTP call, no waiting on a queue, no work that could have been done before the
 * transaction opened. A lock held across a network call to something that is itself struggling is
 * how one slow dependency takes out a table.
 *
 * <p>The other cost is the deadlock, and it is the reason {@code transfer} in the base class sorts
 * the pair by account id before acquiring anything. Two transfers in opposite directions between
 * the same two accounts each take one row and then ask for the other, and neither can give way.
 * Postgres notices after {@code deadlock_timeout} and kills one of them, so it surfaces as an
 * intermittent failure under load rather than as a hang, which is worse: it looks like flakiness.
 * Ordering the acquisition removes it, and {@code PessimisticDeadlockIT} has both halves of that.
 */
class PessimisticLedgerService extends TransactionalLedgerService {

    PessimisticLedgerService(AccountRepository accounts,
                             TransactionTemplate transactions,
                             LedgerMetrics metrics) {
        super(accounts, transactions, metrics);
    }

    @Override
    public String strategy() {
        return "pessimistic";
    }

    @Override
    protected Account acquire(UUID id) {
        return accounts.findByIdForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
