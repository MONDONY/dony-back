package com.dony.api.referral;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReferralCodeRepository extends JpaRepository<ReferralCodeEntity, UUID> {

    Optional<ReferralCodeEntity> findByUserId(UUID userId);

    Optional<ReferralCodeEntity> findByCode(String code);

    boolean existsByCode(String code);
}
