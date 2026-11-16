package com.iliyanadov.walletledger.account;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID accountId) {
        super("No account " + accountId);
    }
}
