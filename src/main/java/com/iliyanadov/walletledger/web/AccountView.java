package com.iliyanadov.walletledger.web;

import com.iliyanadov.walletledger.account.Account;

import java.util.UUID;

/**
 * An account as the API returns it.
 *
 * <p>{@code spendable} is available minus locked. It is derived rather than stored, and it is
 * sent because it is the number a caller actually wants and working it out on the client is one
 * more place to get the subtraction the wrong way round.
 */
public record AccountView(UUID id, long available, long locked, long spendable) {

    public static AccountView of(Account account) {
        return new AccountView(account.id(), account.available(), account.locked(), account.spendable());
    }
}
