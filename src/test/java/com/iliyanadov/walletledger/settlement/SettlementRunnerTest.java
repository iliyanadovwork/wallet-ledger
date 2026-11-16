package com.iliyanadov.walletledger.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;

/**
 * The guard that keeps one account's settlement job from running twice at once.
 *
 * <p>No database and no Spring. The thing under test is a set and a {@code finally}, and putting
 * a container underneath it would only make the failures slower to read.
 */
class SettlementRunnerTest {

    private final SettlementRunner runner = new SettlementRunner();

    @Test
    @DisplayName("a second run for the same account is turned away while the first is still going")
    void theSameAccountCannotRunTwiceAtOnce() throws Exception {
        UUID account = UUID.randomUUID();
        CountDownLatch firstJobHasStarted = new CountDownLatch(1);
        CountDownLatch letTheFirstJobFinish = new CountDownLatch(1);
        AtomicBoolean secondJobRan = new AtomicBoolean();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> first = pool.submit(() -> runner.runFor(account, () -> {
                firstJobHasStarted.countDown();
                await(letTheFirstJobFinish);
            }));

            assertThat(firstJobHasStarted.await(5, TimeUnit.SECONDS)).isTrue();

            boolean secondRan = runner.runFor(account, () -> secondJobRan.set(true));

            assertThat(secondRan).as("the second call is told the account is busy").isFalse();
            assertThat(secondJobRan).as("and the job it was given never ran").isFalse();

            letTheFirstJobFinish.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            letTheFirstJobFinish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("two different accounts settle at the same time and do not wait for each other")
    void differentAccountsDoNotBlockEachOther() throws Exception {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();

        // Both jobs wait at this barrier. If the runner serialised unrelated accounts, the first
        // one in would wait for a partner that cannot arrive and the barrier would time out.
        CyclicBarrier bothInside = new CyclicBarrier(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> runner.runFor(one, () -> awaitBarrier(bothInside)));
            Future<Boolean> second = pool.submit(() -> runner.runFor(two, () -> awaitBarrier(bothInside)));

            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("an account is released even when its job throws")
    void aThrowingJobDoesNotStrandTheAccount() {
        UUID account = UUID.randomUUID();

        assertThatRuntimeException().isThrownBy(() ->
                runner.runFor(account, () -> {
                    throw new IllegalStateException("the settlement blew up");
                }));

        // Release it at the end of the try block instead of in a finally and this next call
        // returns false, forever, for this one account. Nothing logs it and nothing alerts on it.
        // The account simply stops settling while every other account carries on.
        assertThat(runner.runFor(account, () -> { })).isTrue();
        assertThat(runner.inFlight()).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test to release the job");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("The two jobs were not inside the runner at the same time", failure);
        }
    }
}
