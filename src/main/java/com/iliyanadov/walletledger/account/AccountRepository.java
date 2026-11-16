package com.iliyanadov.walletledger.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Reads the row and holds it until the surrounding transaction ends. Another writer asking
     * for the same row waits.
     *
     * <p>On Postgres this emits {@code select ... for no key update}, not
     * {@code for update}, because that is what Hibernate's dialect maps
     * {@code PESSIMISTIC_WRITE} to. The weaker lock is the one worth having: it still shuts out
     * other writers, and it does not conflict with the {@code FOR KEY SHARE} that Postgres takes
     * while checking a foreign key, so an insert referencing this account does not queue behind a
     * transfer.
     *
     * <p>Two callers that take two of these in opposite orders will deadlock, so callers take
     * them in account id order. See {@code PessimisticLedgerService}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}
