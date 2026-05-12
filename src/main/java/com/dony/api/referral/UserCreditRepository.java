package com.dony.api.referral;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserCreditRepository extends JpaRepository<UserCreditEntity, UUID> {

    List<UserCreditEntity> findByUserId(UUID userId);

    @Query("SELECT COALESCE(SUM(c.amountCents), 0) FROM UserCreditEntity c WHERE c.userId = :userId")
    int sumAmountCentsByUserId(@Param("userId") UUID userId);
}
