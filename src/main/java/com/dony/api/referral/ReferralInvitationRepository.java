package com.dony.api.referral;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferralInvitationRepository extends JpaRepository<ReferralInvitationEntity, UUID> {

    List<ReferralInvitationEntity> findByReferrerUserIdOrderByCreatedAtDesc(UUID referrerUserId);

    Optional<ReferralInvitationEntity> findByRefereeUserIdAndStatus(UUID refereeUserId, String status);

    long countByReferrerUserId(UUID referrerUserId);

    long countByReferrerUserIdAndStatus(UUID referrerUserId, String status);
}
