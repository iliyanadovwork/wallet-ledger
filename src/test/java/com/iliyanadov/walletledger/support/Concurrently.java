package com.iliyanadov.walletledger.support;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the same piece of work on several threads at once and reports what happened.
 *
 * <p>The starting gate is the part that matters. Submitting tasks to a pool and letting them go
 * means the first thread is often finished before the last one has started, and a race needs the
 * threads to be inside the same operation at the same time. Every thread blocks on one latch and
 * they are all released together.
 */
public final class Concurrently {

    private Concurrently() {
    }

    public record Outcome(int attempted, int succeeded, List<Throwable> failures) {

        public int failed() {
            return failures.size();
        }

        public boolean allSucceeded() {
            return failures.isEmpty();
        }
    }

    public static Outcome run(int threads, int operationsPerThread, Runnable work) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startingGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        try {
            for (int thread = 0; thread < threads; thread++) {
                pool.submit(() -> {
                    try {
                        startingGate.await();
                        for (int operation = 0; operation < operationsPerThread; operation++) {
                            try {
                                work.run();
                                succeeded.incrementAndGet();
                            } catch (RuntimeException failure) {
                                failures.add(failure);
                            }
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            startingGate.countDown();
            if (!finished.await(3, TimeUnit.MINUTES)) {
                throw new IllegalStateException("The workload did not finish within three minutes");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the workload", interrupted);
        } finally {
            pool.shutdownNow();
        }

        return new Outcome(threads * operationsPerThread, succeeded.get(), List.copyOf(failures));
    }
}
