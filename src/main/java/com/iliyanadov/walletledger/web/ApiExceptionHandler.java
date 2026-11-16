package com.iliyanadov.walletledger.web;

import com.iliyanadov.walletledger.account.AccountNotFoundException;
import com.iliyanadov.walletledger.account.InsufficientFundsException;
import com.iliyanadov.walletledger.account.InvariantViolationException;
import com.iliyanadov.walletledger.idempotency.DuplicateRequestException;
import com.iliyanadov.walletledger.ledger.ContendedAccountException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the domain's refusals into status codes.
 *
 * <p>The split that matters is between "you asked for something you cannot have", which is the
 * caller's answer and final, and "try again shortly", which is contention and will probably work
 * next time. Returning 500 for the second sends a retryable situation to an on-call engineer.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail notFound(AccountNotFoundException failure) {
        return problem(HttpStatus.NOT_FOUND, "Account not found", failure.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException failure) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds", failure.getMessage());
        problem.setProperty("accountId", failure.accountId());
        problem.setProperty("requested", failure.requested());
        problem.setProperty("availableToUse", failure.availableToUse());
        return problem;
    }

    @ExceptionHandler(DuplicateRequestException.class)
    ProblemDetail duplicate(DuplicateRequestException failure) {
        return problem(HttpStatus.CONFLICT, "Duplicate request", failure.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException failure) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", failure.getMessage());
    }

    /**
     * Money is stored in {@code long} minor units and {@code Math.addExact} throws rather than
     * wrapping round. A balance near the top of a signed 64 bit integer is a bad request, not a
     * server fault, so it is answered as one.
     */
    @ExceptionHandler(ArithmeticException.class)
    ProblemDetail overflow(ArithmeticException failure) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Amount out of range",
                "The resulting balance does not fit in a 64 bit integer");
    }

    @ExceptionHandler({ContendedAccountException.class, PessimisticLockingFailureException.class})
    ProblemDetail contended(Exception failure) {
        log.info("Giving a caller a 503 for contention: {}", failure.getMessage());
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "Account is busy",
                "Another request is writing to this account. Nothing has changed. Try again.");
        problem.setProperty("retryAfterSeconds", 1);
        return problem;
    }

    /**
     * Nothing a caller sends should get here. Reaching it means the ledger produced a state it
     * says is impossible, so it is logged at error with the account on it and answered as a fault.
     */
    @ExceptionHandler(InvariantViolationException.class)
    ProblemDetail invariantViolation(InvariantViolationException failure) {
        log.error("Ledger invariant broken", failure);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Ledger invariant broken",
                "The request was rolled back.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
