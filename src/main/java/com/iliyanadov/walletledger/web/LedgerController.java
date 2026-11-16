package com.iliyanadov.walletledger.web;

import com.iliyanadov.walletledger.account.AccountService;
import com.iliyanadov.walletledger.idempotency.DuplicateRequestException;
import com.iliyanadov.walletledger.idempotency.IdempotencyGuard;
import com.iliyanadov.walletledger.ledger.LedgerService;
import com.iliyanadov.walletledger.settlement.SettlementInbox;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The HTTP surface. Deliberately small.
 *
 * <p>Every request that moves money carries an {@code Idempotency-Key} and the header is
 * required, not optional. A client that gets a timeout has no way of knowing whether the payment
 * happened, so it retries, and the only thing that makes that retry safe is the key. Making it
 * optional means the one caller who forgets is the one who double pays.
 *
 * <p>Settlements return 202 rather than 200. They are queued and applied by the settlement job
 * within a tick, so the balances have not moved by the time the response is written and saying
 * 200 would claim they had.
 */
@RestController
@RequestMapping("/api")
@Validated
public class LedgerController {

    private final LedgerService ledger;
    private final AccountService accounts;
    private final SettlementInbox settlements;
    private final IdempotencyGuard idempotency;

    public LedgerController(LedgerService ledger,
                            AccountService accounts,
                            SettlementInbox settlements,
                            IdempotencyGuard idempotency) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.settlements = settlements;
        this.idempotency = idempotency;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView openAccount(@Valid @RequestBody LedgerRequests.OpenAccount request) {
        return AccountView.of(accounts.open(request.openingBalance()));
    }

    @GetMapping("/accounts/{id}")
    public AccountView account(@PathVariable UUID id) {
        return AccountView.of(accounts.get(id));
    }

    @PostMapping("/accounts/{id}/deposits")
    public AccountView deposit(@PathVariable UUID id,
                               @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                               @Valid @RequestBody LedgerRequests.Amount request) {
        return once(idempotencyKey,
                () -> ledger.deposit(id, request.amount()),
                () -> AccountView.of(accounts.get(id)));
    }

    @PostMapping("/accounts/{id}/reservations")
    public AccountView reserve(@PathVariable UUID id,
                               @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                               @Valid @RequestBody LedgerRequests.Amount request) {
        return once(idempotencyKey,
                () -> ledger.reserve(id, request.amount()),
                () -> AccountView.of(accounts.get(id)));
    }

    @PostMapping("/accounts/{id}/releases")
    public AccountView release(@PathVariable UUID id,
                               @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                               @Valid @RequestBody LedgerRequests.Amount request) {
        return once(idempotencyKey,
                () -> ledger.release(id, request.amount()),
                () -> AccountView.of(accounts.get(id)));
    }

    @PostMapping("/accounts/{id}/settlements")
    public ResponseEntity<Map<String, UUID>> settle(@PathVariable UUID id,
                                                    @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                                                    @Valid @RequestBody LedgerRequests.Settlement request) {
        UUID instructionId = once(idempotencyKey,
                () -> settlements.enqueue(id, request.released(), request.settled()));
        return ResponseEntity.accepted().body(Map.of("instructionId", instructionId));
    }

    @PostMapping("/transfers")
    public Map<String, AccountView> transfer(@RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                                             @Valid @RequestBody LedgerRequests.Transfer request) {
        return once(idempotencyKey,
                () -> ledger.transfer(request.from(), request.to(), request.amount()),
                () -> Map.of(
                        "from", AccountView.of(accounts.get(request.from())),
                        "to", AccountView.of(accounts.get(request.to()))));
    }

    /**
     * Claims the key, moves the money, then reads the accounts back for the response.
     *
     * <p>The two halves are separate arguments rather than one block, and that is the whole point
     * of this method. The key is handed back only when the movement itself failed, because then
     * nothing happened and the client's retry is the attempt that should work. Holding a key for a
     * request that did not take effect is worse than having no key at all: the retry is refused as
     * a duplicate of nothing, and the client cannot tell that from success.
     *
     * <p>Once the movement has committed, the key stays claimed whatever happens next. Reading the
     * balances back can fail, the response can fail to serialise, the connection can drop. None of
     * those undo the transfer, so none of them may reopen the key. Wrapping the movement and the
     * read in one try block would surrender the key after the money had already moved, and the
     * retry would move it a second time. That is the exact failure this header exists to prevent,
     * reintroduced by the code meant to prevent it.
     */
    private <T> T once(String idempotencyKey, Runnable movement, Supplier<T> response) {
        claim(idempotencyKey);
        try {
            movement.run();
        } catch (RuntimeException failure) {
            idempotency.surrender(idempotencyKey);
            throw failure;
        }
        return response.get();
    }

    /**
     * The same thing for a request whose single effect produces the response, such as queueing a
     * settlement. There is no second step here, so a failure means nothing was queued and the key
     * goes back.
     */
    private <T> T once(String idempotencyKey, Supplier<T> movement) {
        claim(idempotencyKey);
        try {
            return movement.get();
        } catch (RuntimeException failure) {
            idempotency.surrender(idempotencyKey);
            throw failure;
        }
    }

    private void claim(String idempotencyKey) {
        if (!idempotency.claim(idempotencyKey)) {
            throw new DuplicateRequestException(idempotencyKey);
        }
    }
}
