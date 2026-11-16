package com.iliyanadov.walletledger.web;

import com.iliyanadov.walletledger.account.AccountRepository;
import com.iliyanadov.walletledger.account.AccountService;
import com.iliyanadov.walletledger.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * When the key goes back and when it stays claimed.
 *
 * <p>The request handler does two things in order: it moves the money, then it reads the accounts
 * back to put them in the response. Only the first of those is allowed to hand the key back.
 *
 * <p>The failure this guards against is the one the header exists to prevent, reintroduced by the
 * code meant to prevent it. Wrap both steps in one try block and a transfer that commits but
 * cannot be rendered releases its key, the client retries because it got a 500, and the money
 * moves twice.
 */
class IdempotencyKeyLifecycleIT extends IntegrationTest {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private AccountRepository repository;

    @MockitoSpyBean
    private AccountService accounts;

    @Test
    @DisplayName("a failure after the money has moved keeps the key, so the retry cannot move it again")
    void theKeyIsNotHandedBackOnceTheTransferHasCommitted() {
        UUID payer = accounts.open(100_000).id();
        UUID payee = accounts.open(0).id();
        String key = UUID.randomUUID().toString();

        // The transfer will commit. Reading the payer back for the response will not.
        doThrow(new IllegalStateException("could not read the account back"))
                .when(accounts).get(payer);

        ResponseEntity<String> first = post("/api/transfers",
                Map.of("from", payer, "to", payee, "amount", 5_000), key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(balanceOf(payer))
                .as("the transfer itself committed before the response failed")
                .isEqualTo(95_000);

        ResponseEntity<String> retry = post("/api/transfers",
                Map.of("from", payer, "to", payee, "amount", 5_000), key);

        assertThat(retry.getStatusCode())
                .as("the key is still claimed, because the work it stands for did happen")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(balanceOf(payer))
                .as("and the money moved exactly once")
                .isEqualTo(95_000);
    }

    @Test
    @DisplayName("a failure before anything moved hands the key back, so the retry works")
    void theKeyGoesBackWhenTheMovementItselfFailed() {
        reset(accounts);
        UUID payer = accounts.open(1_000).id();
        UUID payee = accounts.open(0).id();
        String key = UUID.randomUUID().toString();

        // More than the payer has, so the ledger refuses and nothing moves.
        ResponseEntity<String> refused = post("/api/transfers",
                Map.of("from", payer, "to", payee, "amount", 5_000), key);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // Same key, an amount the payer can afford. This is the client's retry and it has to work.
        ResponseEntity<String> retry = post("/api/transfers",
                Map.of("from", payer, "to", payee, "amount", 400), key);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(balanceOf(payer)).isEqualTo(600);
    }

    private long balanceOf(UUID account) {
        return repository.findById(account).orElseThrow().available();
    }

    private ResponseEntity<String> post(String path, Object body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
