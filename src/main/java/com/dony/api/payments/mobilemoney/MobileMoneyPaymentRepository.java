package com.dony.api.payments.mobilemoney;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MobileMoneyPaymentRepository extends JpaRepository<MobileMoneyPaymentEntity, UUID> {
    Optional<MobileMoneyPaymentEntity> findByExternalReference(String externalReference);
    Optional<MobileMoneyPaymentEntity> findTopByBidIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID bidId);
}
