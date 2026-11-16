package com.iliyanadov.walletledger.settlement;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stops an account's settlement job from running twice at once.
 *
 * <p>The job reads an account's pending instructions, applies them and marks them done. Two runs
 * for the same account overlapping means both read the same pending rows before either has marked
 * anything, so both apply them and the account is settled twice. The second one is not a retry. It
 * is a second, wrong, movement of money.
 *
 * <p>A boolean would hold one fact for the whole service: busy or not. It cannot say that account
 * A is busy and account B is free, so it either lets B through while A is running, which is the
 * defect, or it stops B, which turns an unrelated account into a queue. A set holds one answer per
 * account, which is the number of answers the question actually has.
 *
 * <p>{@code ConcurrentHashMap.newKeySet} is the set to reach for. {@code add} returning false is
 * the test and the claim in one step, so two threads arriving together cannot both be told the
 * account is free. Checking membership and then adding would be two steps with a gap between them.
 *
 * <p>The release is in a {@code finally} and that is the part worth being careful about. Put it at
 * the end of the try block instead and a job that throws leaves its account in the set forever.
 * Nothing crashes, no alert fires, and that one account silently stops settling while every other
 * account carries on, which is close to the hardest kind of failure to notice.
 *
 * <p>What this does not do is coordinate two instances of the service, because the set lives in
 * one heap. Running more than one instance needs the claim to happen in the database, by marking
 * the rows taken in the same statement that selects them. Same limitation as the synchronized
 * ledger strategy, same reason.
 */
@Component
public class SettlementRunner {

    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    /**
     * Runs {@code job} unless this account already has a run in progress.
     *
     * @return true if the job ran, false if it was skipped because one was already going
     */
    public boolean runFor(UUID accountId, Runnable job) {
        if (!running.add(accountId)) {
            return false;
        }
        try {
            job.run();
            return true;
        } finally {
            running.remove(accountId);
        }
    }

    /** How many accounts are settling right now. Exposed for the tests and the metrics. */
    public int inFlight() {
        return running.size();
    }
}
