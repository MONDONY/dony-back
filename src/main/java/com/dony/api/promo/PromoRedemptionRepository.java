package com.dony.api.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface PromoRedemptionRepository extends JpaRepository<PromoRedemptionEntity, UUID> {

    /** Nombre de fois que cet utilisateur a utilisé ce promo code. */
    long countByPromoCodeIdAndUserId(UUID promoCodeId, UUID userId);

    /** Idempotence : déjà racheté pour ce bid ? */
    boolean existsByPromoCodeIdAndBidId(UUID promoCodeId, UUID bidId);

    Optional<PromoRedemptionEntity> findByPromoCodeIdAndBidId(UUID promoCodeId, UUID bidId);
}
