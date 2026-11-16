package com.iliyanadov.walletledger.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/** Request bodies. Amounts are minor units, so 1250 is twelve pounds fifty. */
public final class LedgerRequests {

    private LedgerRequests() {
    }

    public record OpenAccount(@Min(0) long openingBalance) {
    }

    public record Amount(@Positive long amount) {
    }

    public record Settlement(@Positive long released, @Min(0) long settled) {
    }

    public record Transfer(@NotNull UUID from, @NotNull UUID to, @Positive long amount) {
    }
}
