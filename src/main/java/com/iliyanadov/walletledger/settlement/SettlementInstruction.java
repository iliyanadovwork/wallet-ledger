package com.iliyanadov.walletledger.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One settlement waiting to be applied: an amount to release from the account's promised part and
 * an amount to take out of it. The two are separate numbers for the reason given on
 * {@code Account#settle}.
 *
 * <p>A row is pending while {@code settledAt} is null. Nothing is deleted, so the table is the
 * record of what was applied and when.
 */
@Entity
@Table(name = "settlement_instruction")
public class SettlementInstruction {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private long released;

    @Column(nullable = false)
    private long settled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    protected SettlementInstruction() {
    }

    private SettlementInstruction(UUID id, UUID accountId, long released, long settled, Instant createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.released = released;
        this.settled = settled;
        this.createdAt = createdAt;
    }

    public static SettlementInstruction pending(UUID id, UUID accountId, long released, long settled, Instant now) {
        return new SettlementInstruction(id, accountId, released, settled, now);
    }

    public void markApplied(Instant now) {
        this.settledAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID accountId() {
        return accountId;
    }

    public long released() {
        return released;
    }

    public long settled() {
        return settled;
    }

    public Instant settledAt() {
        return settledAt;
    }
}
