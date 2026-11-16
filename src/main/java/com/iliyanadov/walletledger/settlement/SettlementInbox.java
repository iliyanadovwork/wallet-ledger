package com.iliyanadov.walletledger.settlement;

import com.iliyanadov.walletledger.account.Account;
import com.iliyanadov.walletledger.account.AccountNotFoundException;
import com.iliyanadov.walletledger.account.AccountRepository;
import com.iliyanadov.walletledger.account.InsufficientFundsException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** Takes settlements in. The scheduler is what applies them. */
@Component
public class SettlementInbox {

    private final SettlementInstructionRepository instructions;
    private final AccountRepository accounts;
    private final Clock clock;

    public SettlementInbox(SettlementInstructionRepository instructions,
                           AccountRepository accounts,
                           Clock clock) {
        this.instructions = instructions;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public UUID enqueue(UUID accountId, long released, long settled) {
        if (released <= 0) {
            throw new IllegalArgumentException("Released amount must be positive, got " + released);
        }
        if (settled < 0) {
            throw new IllegalArgumentException("Settled amount must not be negative, got " + settled);
        }
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        // A sanity check at the door, not the guarantee. The balances can move between now and
        // the job picking this up, so Account.settle is what actually decides. What this catches
        // is the instruction that could never have applied at any point: released is checked
        // against a reservation that does not exist. Without it, one such request wedges that
        // account's queue behind an instruction that will fail on every tick for the life of the
        // row, and the caller gets a 202 for it.
        if (account.locked() < released) {
            throw new InsufficientFundsException(accountId, released, account.locked());
        }

        UUID id = UUID.randomUUID();
        instructions.save(SettlementInstruction.pending(id, accountId, released, settled, clock.instant()));
        return id;
    }
}
