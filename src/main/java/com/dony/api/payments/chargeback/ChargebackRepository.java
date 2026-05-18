package com.dony.api.payments.chargeback;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ChargebackRepository extends JpaRepository<ChargebackEntity, UUID> {
    Optional<ChargebackEntity> findByStripeDisputeId(String disputeId);
    Page<ChargebackEntity> findAllByOrderByOpenedAtDesc(Pageable pageable);
}
