package com.iliyanadov.walletledger.ledger;

/**
 * The optimistic strategy gave up.
 *
 * <p>It ran the unit of work the configured number of times and lost the version check every time,
 * which means something else is writing to the same account faster than this caller can finish.
 * The right answer for a caller is to slow down and come back, so this maps to 503 rather than
 * 500. It is contention, not a fault.
 *
 * <p>The cause is the last conflict, and Spring puts the account id in its message.
 */
public class ContendedAccountException extends RuntimeException {

    public ContendedAccountException(String operation, int attempts, Throwable lastConflict) {
        super("Gave up on %s after %d attempts, every one of them lost to a concurrent write"
                .formatted(operation, attempts), lastConflict);
    }
}
