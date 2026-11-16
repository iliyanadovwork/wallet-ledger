package com.iliyanadov.walletledger.account;

import java.util.UUID;

/** The account was asked for more than it had to give. An ordinary refusal, not a fault. */
public class InsufficientFundsException extends RuntimeException {

    private final UUID accountId;
    private final long requested;
    private final long availableToUse;

    public InsufficientFundsException(UUID accountId, long requested, long availableToUse) {
        super("Account %s was asked for %d and has %d".formatted(accountId, requested, availableToUse));
        this.accountId = accountId;
        this.requested = requested;
        this.availableToUse = availableToUse;
    }

    public UUID accountId() {
        return accountId;
    }

    public long requested() {
        return requested;
    }

    public long availableToUse() {
        return availableToUse;
    }
}
