package com.iliyanadov.walletledger.account;

import java.util.UUID;

/**
 * The account ended an operation in a state the ledger says is impossible.
 *
 * <p>Nothing a caller does should produce this. Every operation checks its own preconditions
 * first and refuses with {@link InsufficientFundsException} when they fail, so reaching here
 * means the arithmetic or the locking is wrong. It is thrown rather than logged so the
 * transaction rolls back and the bad state never reaches the table.
 */
public class InvariantViolationException extends RuntimeException {

    public InvariantViolationException(UUID accountId, String operation, long available, long locked) {
        super("Account %s broke 0 <= locked <= available during %s: available=%d, locked=%d"
                .formatted(accountId, operation, available, locked));
    }
}
