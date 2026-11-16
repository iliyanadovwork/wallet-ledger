package com.iliyanadov.walletledger.ledger;

import com.iliyanadov.walletledger.account.AccountRepository;
import com.iliyanadov.walletledger.account.AccountService;
import com.iliyanadov.walletledger.settlement.SettlementInbox;
import com.iliyanadov.walletledger.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * How much the pessimistic strategy's row lock actually blocks.
 *
 * <p>Worth pinning down, because the lock is not quite the one the code asks for.
 * {@code @Lock(PESSIMISTIC_WRITE)} on Postgres does not emit {@code SELECT ... FOR UPDATE}. The
 * dialect turns it into {@code SELECT ... FOR NO KEY UPDATE}, which is the weaker of the two
 * write locks, and the difference is visible in behaviour rather than only in the SQL.
 *
 * <p>The part that matters is what {@code FOR NO KEY UPDATE} leaves alone. Postgres takes
 * {@code FOR KEY SHARE} on a row when something inserts a reference to it, so every insert into
 * {@code settlement_instruction} takes one on its account. {@code FOR UPDATE} would conflict with
 * that and queue those inserts behind any transfer in progress, for no gain, since a settlement
 * arriving cannot change an account's id. {@code FOR NO KEY UPDATE} does not conflict with it.
 *
 * <p>What it does still conflict with is another writer on the same row, which is the entire
 * point of taking it. Both halves are tested here, because a lock that blocks nothing and a lock
 * that blocks everything are both wrong and only one test tells them apart.
 */
class PessimisticLockScopeIT extends IntegrationTest {

    @Autowired
    private AccountRepository repository;

    @Autowired
    private AccountService accounts;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private SettlementInbox inbox;

    @Autowired
    private LedgerService ledger;

    @Test
    @DisplayName("queueing a settlement does not wait behind a transfer holding the account row")
    void aForeignKeyCheckDoesNotWaitForTheRowLock() throws Exception {
        UUID account = accounts.open(10_000).id();
        ledger.reserve(account, 5_000);

        CountDownLatch lockTaken = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
                repository.findByIdForUpdate(account).orElseThrow();
                lockTaken.countDown();
                awaitQuietly(letGo);
            }));
            assertThat(lockTaken.await(20, TimeUnit.SECONDS)).isTrue();

            // Inserts a settlement_instruction row whose account_id points at the locked account,
            // so Postgres checks the foreign key and takes FOR KEY SHARE on it. Under FOR UPDATE
            // this would sit here until the holder committed.
            UUID instructionId = assertTimeoutPreemptively(Duration.ofSeconds(15),
                    () -> inbox.enqueue(account, 5_000, 5_000));

            assertThat(instructionId).isNotNull();

            letGo.countDown();
            holder.get(20, TimeUnit.SECONDS);
        } finally {
            letGo.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a second writer does wait for the same account row")
    void anotherWriterWaitsForTheRowLock() throws Exception {
        UUID account = accounts.open(10_000).id();

        CountDownLatch lockTaken = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
                repository.findByIdForUpdate(account).orElseThrow();
                lockTaken.countDown();
                awaitQuietly(letGo);
            }));
            assertThat(lockTaken.await(20, TimeUnit.SECONDS)).isTrue();

            Future<?> secondWriter = pool.submit(() -> transactions.executeWithoutResult(status ->
                    repository.findByIdForUpdate(account).orElseThrow()));

            // It cannot get through while the first transaction is parked on the latch, so this
            // is a wait rather than a slow machine.
            assertThatThrownBy(() -> secondWriter.get(2, TimeUnit.SECONDS))
                    .as("the weaker lock still excludes another writer, which is what it is for")
                    .isInstanceOf(TimeoutException.class);

            letGo.countDown();
            secondWriter.get(20, TimeUnit.SECONDS);
            holder.get(20, TimeUnit.SECONDS);
        } finally {
            letGo.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out holding the row lock");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
