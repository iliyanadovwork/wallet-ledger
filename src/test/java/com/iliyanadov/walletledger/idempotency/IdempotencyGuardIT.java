package com.iliyanadov.walletledger.idempotency;

import com.iliyanadov.walletledger.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim on an idempotency key, against a real Redis.
 *
 * <p>The property is that of N callers arriving together with the same key, exactly one is told
 * it got it. That holds because the claim is one command. Redis runs commands one at a time, so
 * {@code SET NX} deciding whether the key was already there and setting it are not two things that
 * can be interleaved. Asking {@code EXISTS} and then calling {@code SET} would be two things, and
 * it would have the same shape as the unguarded ledger.
 */
class IdempotencyGuardIT extends IntegrationTest {

    private static final int CALLERS = 24;

    @Autowired
    private IdempotencyGuard guard;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("exactly one of many simultaneous claims on the same key succeeds")
    void onlyOneCallerGetsTheKey() throws Exception {
        String key = UUID.randomUUID().toString();
        CountDownLatch startingGate = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);
        try {
            List<Callable<Boolean>> callers = IntStream.range(0, CALLERS)
                    .mapToObj(i -> (Callable<Boolean>) () -> {
                        startingGate.await();
                        return guard.claim(key);
                    })
                    .toList();

            List<Future<Boolean>> claims = callers.stream().map(pool::submit).toList();
            startingGate.countDown();

            long succeeded = 0;
            for (Future<Boolean> claim : claims) {
                if (claim.get(20, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }

            assertThat(succeeded)
                    .as("%d callers, one key, one winner", CALLERS)
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a claimed key is refused to everybody afterwards")
    void aSecondClaimIsRefused() {
        String key = UUID.randomUUID().toString();

        assertThat(guard.claim(key)).isTrue();
        assertThat(guard.claim(key)).isFalse();
        assertThat(guard.claim(key)).isFalse();
    }

    @Test
    @DisplayName("giving a key back lets the retry through")
    void surrenderReopensTheKey() {
        String key = UUID.randomUUID().toString();

        assertThat(guard.claim(key)).isTrue();
        guard.surrender(key);

        assertThat(guard.claim(key))
                .as("the work failed, so the client's retry is the attempt that should work")
                .isTrue();
    }

    @Test
    @DisplayName("the key expires, so the key set does not grow forever")
    void theKeyHasATimeToLive() {
        String key = UUID.randomUUID().toString();
        guard.claim(key);

        Long secondsLeft = redis.getExpire("idempotency:" + key);

        assertThat(secondsLeft)
                .as("a key with no expiry is a leak, and it bounds the guarantee too")
                .isNotNull()
                .isGreaterThan(0L);
    }
}
