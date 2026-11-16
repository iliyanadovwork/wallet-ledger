package com.iliyanadov.walletledger.web;

import com.iliyanadov.walletledger.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP surface, end to end, against the containers.
 *
 * <p>The test that matters here is the double submit. Everything else is wiring: the right status
 * codes, the right numbers in the body, the header being required. The double submit is the one
 * that would let a customer be charged twice.
 */
class LedgerApiIT extends IntegrationTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("an account can be opened, paid into and read back")
    void theOrdinaryPath() {
        UUID account = openAccount(0);

        ResponseEntity<Map<String, Object>> deposited = post("/api/accounts/" + account + "/deposits",
                Map.of("amount", 25_000), freshKey());

        assertThat(deposited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deposited.getBody()).containsEntry("available", 25_000);
        assertThat(deposited.getBody()).containsEntry("spendable", 25_000);

        ResponseEntity<Map<String, Object>> read = get("/api/accounts/" + account);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody()).containsEntry("available", 25_000);
    }

    @Test
    @DisplayName("the same transfer submitted many times at once is applied once")
    void aDoubleSubmitMovesTheMoneyOnce() throws Exception {
        UUID payer = openAccount(100_000);
        UUID payee = openAccount(0);
        String sharedKey = freshKey();
        int submissions = 16;

        CountDownLatch startingGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(submissions);
        List<Future<HttpStatus>> responses;
        try {
            List<Callable<HttpStatus>> submits = IntStream.range(0, submissions)
                    .mapToObj(i -> (Callable<HttpStatus>) () -> {
                        startingGate.await();
                        return (HttpStatus) post("/api/transfers",
                                Map.of("from", payer, "to", payee, "amount", 5_000),
                                sharedKey).getStatusCode();
                    })
                    .toList();

            responses = submits.stream().map(pool::submit).toList();
            startingGate.countDown();

            long accepted = 0;
            long refusedAsDuplicate = 0;
            for (Future<HttpStatus> response : responses) {
                HttpStatus status = response.get(30, TimeUnit.SECONDS);
                if (status.is2xxSuccessful()) {
                    accepted++;
                } else if (status == HttpStatus.CONFLICT) {
                    refusedAsDuplicate++;
                }
            }

            assertThat(accepted).as("one submission does the work").isEqualTo(1);
            assertThat(refusedAsDuplicate).as("and the rest are told it is already done")
                    .isEqualTo(submissions - 1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(availableOf(payer)).isEqualTo(95_000);
        assertThat(availableOf(payee)).isEqualTo(5_000);
    }

    @Test
    @DisplayName("a request that moves money without an idempotency key is refused")
    void theHeaderIsRequired() {
        UUID account = openAccount(1_000);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> response = http.exchange("/api/accounts/" + account + "/deposits",
                HttpMethod.POST, new HttpEntity<>(Map.of("amount", 100), headers), BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("spending money that is already reserved is refused with 422 and an explanation")
    void insufficientFundsExplainsItself() {
        UUID payer = openAccount(1_000);
        UUID payee = openAccount(0);
        post("/api/accounts/" + payer + "/reservations", Map.of("amount", 900), freshKey());

        ResponseEntity<Map<String, Object>> response = post("/api/transfers",
                Map.of("from", payer, "to", payee, "amount", 500), freshKey());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("title", "Insufficient funds");
        assertThat(response.getBody()).containsEntry("requested", 500);
        assertThat(response.getBody()).containsEntry("availableToUse", 100);
    }

    @Test
    @DisplayName("an unknown account is a 404")
    void unknownAccount() {
        ResponseEntity<Map<String, Object>> response = get("/api/accounts/" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a settlement is accepted rather than applied, so it answers 202")
    void settlementsAreQueued() {
        UUID account = openAccount(10_000);
        post("/api/accounts/" + account + "/reservations", Map.of("amount", 5_000), freshKey());

        ResponseEntity<Map<String, Object>> response = post("/api/accounts/" + account + "/settlements",
                Map.of("released", 5_000, "settled", 5_000), freshKey());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsKey("instructionId");

        // The balances have not moved yet, which is precisely why this is a 202 and not a 200.
        assertThat(availableOf(account)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("a negative amount is a bad request before it reaches the ledger")
    void amountsAreValidatedAtTheEdge() {
        UUID account = openAccount(1_000);

        ResponseEntity<Map<String, Object>> response = post("/api/accounts/" + account + "/deposits",
                Map.of("amount", -50), freshKey());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Responses are read as plain maps. These tests are about status codes and numbers. */
    private static final ParameterizedTypeReference<Map<String, Object>> BODY =
            new ParameterizedTypeReference<>() {
            };

    private UUID openAccount(long openingBalance) {
        ResponseEntity<Map<String, Object>> response = http.exchange("/api/accounts", HttpMethod.POST,
                new HttpEntity<>(Map.of("openingBalance", openingBalance), jsonHeaders(null)), BODY);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private long availableOf(UUID account) {
        return ((Number) get("/api/accounts/" + account).getBody().get("available")).longValue();
    }

    private ResponseEntity<Map<String, Object>> get(String path) {
        return http.exchange(path, HttpMethod.GET, HttpEntity.EMPTY, BODY);
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body, String idempotencyKey) {
        return http.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(idempotencyKey)), BODY);
    }

    private static HttpHeaders jsonHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private static String freshKey() {
        return UUID.randomUUID().toString();
    }
}
