package com.dony.api.payments.wallet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WalletTopupRequestRepository extends JpaRepository<WalletTopupRequestEntity, UUID> {
    Optional<WalletTopupRequestEntity> findByExternalReference(String externalReference);
}
