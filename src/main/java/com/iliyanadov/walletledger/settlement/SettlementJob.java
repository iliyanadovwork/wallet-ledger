package com.iliyanadov.walletledger.settlement;

import com.iliyanadov.walletledger.ledger.LedgerService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Applies everything waiting on one account, in the order it was queued.
 *
 * <p>The ledger call and the mark are in one transaction. Apart, a crash between them leaves an
 * instruction that has already moved money and still looks pending, and the next run applies it
 * again.
 *
 * <p>Each instruction gets its own transaction rather than one covering the batch. One
 * transaction for the batch would be shorter to write and would mean a single bad instruction
 * rolls back the good ones next to it, which are then reapplied on the next tick and roll back
 * again.
 *
 * <p>The transaction is opened here rather than with {@code @Transactional} because the method
 * that needs it is called from the method above it in this same class. Spring's annotation works
 * through a proxy and a call from one method of a bean to another does not go through it, so the
 * annotation would have been silently ignored and the two steps would have committed separately.
 *
 * <p><b>An instruction that will not apply stops the account.</b> The run gives up at that
 * instruction and reports it rather than skipping past it, and nothing queued behind it is
 * applied until it clears. That is the deliberate half of the trade. Settlements for one account
 * are ordered, and applying a later one first means money moves in an order the caller did not
 * ask for, which is harder to unpick afterwards than a queue that stopped. The cost is that one
 * account stops settling, so the run says which instruction it stopped on and the scheduler puts
 * that in the log and in the health signal. A queue that stops loudly is a different thing from
 * one that stops quietly.
 */
@Component
public class SettlementJob {

    private final SettlementInstructionRepository instructions;
    private final LedgerService ledger;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SettlementJob(SettlementInstructionRepository instructions,
                         LedgerService ledger,
                         TransactionTemplate transactions,
                         Clock clock) {
        this.instructions = instructions;
        this.ledger = ledger;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * What one run did.
     *
     * @param applied   how many instructions were applied
     * @param stalledOn the instruction the run gave up at, or null if it got through them all
     * @param failure   why it gave up, or null
     */
    public record Outcome(int applied, UUID stalledOn, RuntimeException failure) {

        public boolean stalled() {
            return stalledOn != null;
        }
    }

    public Outcome settleAccount(UUID accountId) {
        List<UUID> pending = instructions.pendingFor(accountId).stream()
                .map(SettlementInstruction::id)
                .toList();

        int applied = 0;
        for (UUID instructionId : pending) {
            try {
                if (apply(instructionId)) {
                    applied++;
                }
            } catch (RuntimeException failure) {
                return new Outcome(applied, instructionId, failure);
            }
        }
        return new Outcome(applied, null, null);
    }

    private boolean apply(UUID instructionId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            SettlementInstruction instruction = instructions.findById(instructionId).orElse(null);
            if (instruction == null || instruction.settledAt() != null) {
                return false;
            }
            ledger.settle(instruction.accountId(), instruction.released(), instruction.settled());
            instruction.markApplied(clock.instant());
            return true;
        }));
    }
}
