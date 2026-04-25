package com.dony.api.payments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByBidId(UUID bidId);

    Optional<PaymentEntity> findByStripePaymentIntentId(String stripePaymentIntentId);

    List<PaymentEntity> findByStatus(PaymentStatus status);
}
