package com.iliyanadov.walletledger.idempotency;

import com.iliyanadov.walletledger.ledger.LedgerProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Makes a repeated request apply once.
 *
 * <p>A client that does not get an answer does not know whether the work happened, so it sends the
 * request again. Somewhere between a retry button, a proxy timeout and a mobile network changing
 * its mind, the same payment arrives twice. The caller puts an {@code Idempotency-Key} on the
 * request and the first one to claim that key is the one that runs.
 *
 * <p>The claim is one Redis command. {@code SET key value NX PX ttl} either sets the key or
 * reports that it was already set, and Redis decides that on its own, so there is no read followed
 * by a write for two callers to interleave. Asking whether the key exists and then setting it
 * would be the same check-early-act-late shape as the unguarded ledger, in a different colour.
 *
 * <p>The TTL is what stops the key set growing forever. It also bounds the guarantee, and the
 * honest way to describe it is that a request cannot be replayed within the window rather than
 * that it can never be replayed. A day is long enough to cover every retry a client will actually
 * make.
 *
 * <p>A duplicate is refused rather than replayed. That costs the caller something: they learn the
 * request was already accepted, not what the first one returned. Replaying the original response
 * means storing it, which means deciding what happens when the first request is still in flight,
 * and that is a larger piece of work than this service needs.
 */
@Component
public class IdempotencyGuard {

    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public IdempotencyGuard(StringRedisTemplate redis, LedgerProperties properties) {
        this.redis = redis;
        this.ttl = properties.idempotencyTtl();
    }

    /**
     * Claims the key for this caller.
     *
     * @return true if this caller got it, false if somebody already had it
     */
    public boolean claim(String idempotencyKey) {
        Boolean claimed = redis.opsForValue().setIfAbsent(redisKey(idempotencyKey), "claimed", ttl);
        return Boolean.TRUE.equals(claimed);
    }

    /**
     * Gives the key back.
     *
     * <p>Called when the work itself failed. Holding a key for a request that did not happen means
     * the retry, which is the request that would have worked, is refused as a duplicate. The
     * client is then stuck until the TTL runs out with no way to tell that from a success.
     */
    public void surrender(String idempotencyKey) {
        redis.delete(redisKey(idempotencyKey));
    }

    private static String redisKey(String idempotencyKey) {
        return KEY_PREFIX + idempotencyKey;
    }
}
