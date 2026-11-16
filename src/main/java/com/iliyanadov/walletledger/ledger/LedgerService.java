package com.iliyanadov.walletledger.ledger;

import java.util.UUID;

/**
 * Five operations on account balances.
 *
 * <p>Four implementations of this interface exist and they all do the same arithmetic. What they
 * differ in is how two callers touching one account are kept apart, which is the whole subject of
 * this project. One of them does not keep them apart at all and is there to be broken.
 *
 * <p>Amounts are minor units and must be positive. An operation either happens completely or not
 * at all.
 */
public interface LedgerService {

    /** Which implementation this is. Used by the metrics tags and by the tests that run all of them. */
    String strategy();

    /** Money in. */
    void deposit(UUID accountId, long amount);

    /** Promise an amount to something, leaving it in the account but no longer spendable. */
    void reserve(UUID accountId, long amount);

    /** Drop a promise without acting on it. */
    void release(UUID accountId, long amount);

    /**
     * Act on a promise. {@code released} comes off the promised part, {@code settled} comes out of
     * the account, and the two do not have to match. See {@code Account#settle}.
     */
    void settle(UUID accountId, long released, long settled);

    /** Move money between two accounts. The total across both is the same before and after. */
    void transfer(UUID fromId, UUID toId, long amount);
}
