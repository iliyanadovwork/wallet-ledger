package com.iliyanadov.walletledger.idempotency;

/** The idempotency key on this request has been used already. The work is not done twice. */
public class DuplicateRequestException extends RuntimeException {

    public DuplicateRequestException(String idempotencyKey) {
        super("Idempotency key " + idempotencyKey + " has already been used");
    }
}
