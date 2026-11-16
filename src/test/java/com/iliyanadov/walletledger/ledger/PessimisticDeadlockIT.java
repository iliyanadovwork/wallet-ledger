package com.iliyanadov.walletledger.ledger;

import com.iliyanadov.walletledger.account.AccountRepository;
import com.iliyanadov.walletledger.account.AccountService;
import com.iliyanadov.walletledger.support.Concurrently;
import com.iliyanadov.walletledger.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure that has no single threaded version of itself.
 *
 * <p>A race is two threads doing the same thing in the wrong order and you can talk yourself
 * through it one step at a time. A deadlock is not like that. Both threads are behaving correctly,
 * neither has done anything out of order, and the system stops because each one is waiting for
 * something the other is holding. There is no sequence of single threaded steps that produces it.
 *
 * <p>The first test builds one. Two threads, two accounts, locks taken in opposite orders, and a
 * barrier so that neither asks for its second lock until both are holding their first. Postgres
 * waits {@code deadlock_timeout}, works out that the wait graph has a cycle in it, and kills one
 * of the two transactions.
 *
 * <p>The way that presents itself in production is the part worth knowing. It is not a hang. One
 * request in some small fraction fails, under load, and only when two particular transfers happen
 * to cross. It looks like flakiness, which is how it survives a code review and a week of
 * staging.
 *
 * <p>The second test is the fix. {@code TransactionalLedgerService.transfer} sorts the pair by
 * account id before it acquires anything, so every caller in the system queues for the lower id
 * first. Two transfers going in opposite directions between the same two accounts now want the
 * same lock first, one of them gets it, and the other waits for it rather than for something the
 * first one is about to need. The cycle cannot form because nobody ever asks for the locks in the
 * other order.
 */
class PessimisticDeadlockIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PessimisticDeadlockIT.class);

    @Autowired
    private PessimisticLedgerService pessimistic;

    @Autowired
    private AccountRepository repository;

    @Autowired
    private AccountService accounts;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    @DisplayName("two transactions that take the same two row locks in opposite orders deadlock")
    void lockingInArgumentOrderDeadlocks() throws Exception {
        UUID left = accounts.open(10_000).id();
        UUID right = accounts.open(10_000).id();

        CyclicBarrier bothHoldTheirFirstLock = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> one = pool.submit(() -> lockInOrder(left, right, bothHoldTheirFirstLock));
            Future<Throwable> two = pool.submit(() -> lockInOrder(right, left, bothHoldTheirFirstLock));

            List<Throwable> failures = new ArrayList<>();
            addIfPresent(failures, one.get(60, TimeUnit.SECONDS));
            addIfPresent(failures, two.get(60, TimeUnit.SECONDS));

            assertThat(failures)
                    .as("Postgres breaks the cycle by killing exactly one of the two transactions")
                    .hasSize(1);

            Throwable loser = failures.get(0);
            log.info("Deadlock resolved by the database: {}", loser.getMessage());

            assertThat(loser).isInstanceOf(PessimisticLockingFailureException.class);
            assertThat(describe(loser).toLowerCase()).contains("deadlock");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the same crossed transfers succeed when the ledger orders locks by account id")
    void orderingLocksByAccountIdRemovesTheDeadlock() {
        UUID left = accounts.open(1_000_000).id();
        UUID right = accounts.open(1_000_000).id();
        AtomicInteger direction = new AtomicInteger();

        // Half the threads transfer left to right and half go the other way, which is exactly the
        // pattern the first test turns into a deadlock.
        Concurrently.Outcome outcome = Concurrently.run(12, 30, () -> {
            boolean leftToRight = direction.getAndIncrement() % 2 == 0;
            if (leftToRight) {
                pessimistic.transfer(left, right, 100);
            } else {
                pessimistic.transfer(right, left, 100);
            }
        });

        assertThat(outcome.allSucceeded())
                .as("no transfer should fail, and a deadlock here would show up as one that did")
                .isTrue();

        assertThat(accounts.get(left).available() + accounts.get(right).available())
                .isEqualTo(2_000_000);
    }

    /**
     * Opens a transaction, takes a row lock on {@code first}, waits for the other thread to take
     * its own first lock, then asks for {@code second}. Returns the exception if the database
     * killed this transaction, or null if it got through.
     */
    private Throwable lockInOrder(UUID first, UUID second, CyclicBarrier bothHoldTheirFirstLock) {
        try {
            transactions.executeWithoutResult(status -> {
                repository.findByIdForUpdate(first).orElseThrow();
                waitFor(bothHoldTheirFirstLock);
                repository.findByIdForUpdate(second).orElseThrow();
            });
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private static void waitFor(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (BrokenBarrierException | java.util.concurrent.TimeoutException broken) {
            throw new IllegalStateException(broken);
        }
    }

    private static void addIfPresent(List<Throwable> failures, Throwable failure) {
        if (failure != null) {
            failures.add(failure);
        }
    }

    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable link = failure; link != null; link = link.getCause()) {
            text.append(link.getMessage()).append(' ');
            if (link.getCause() == link) {
                break;
            }
        }
        return text.toString();
    }
}
