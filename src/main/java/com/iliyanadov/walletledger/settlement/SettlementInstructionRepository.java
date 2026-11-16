package com.iliyanadov.walletledger.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface SettlementInstructionRepository extends JpaRepository<SettlementInstruction, UUID> {

    @Query("select i from SettlementInstruction i where i.accountId = :accountId and i.settledAt is null "
            + "order by i.createdAt")
    List<SettlementInstruction> pendingFor(UUID accountId);

    @Query("select distinct i.accountId from SettlementInstruction i where i.settledAt is null")
    List<UUID> accountsWithPendingWork();
}
