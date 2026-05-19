package com.dony.api.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByFirebaseUid(String firebaseUid);
    Optional<UserEntity> findByStripeAccountId(String stripeAccountId);
    Optional<UserEntity> findByStripeCustomerId(String stripeCustomerId);
    Optional<UserEntity> findByCommissionPaymentMethodId(String commissionPaymentMethodId);

    boolean existsByFirebaseUid(String firebaseUid);
    boolean existsByPhoneNumber(String phoneNumber);
    boolean existsByEmail(String email);

    Optional<UserEntity> findByEmail(String email);

    /**
     * Loads a UserEntity with a pessimistic write lock to prevent concurrent
     * Stripe Connect account creation (race condition guard in createConnectAccount).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.id = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Finds users with a given status whose deletion grace period has expired.
     * Used to identify accounts ready for final permanent deletion.
     */
    List<UserEntity> findByStatusAndDeletionRequestedAtBefore(UserStatus status, Instant cutoff);

    @Query("SELECT u FROM UserEntity u WHERE u.commissionPaymentMethodId IS NOT NULL AND " +
           "(u.commissionCardExpYear < :year OR " +
           "(u.commissionCardExpYear = :year AND u.commissionCardExpMonth <= :month))")
    List<UserEntity> findUsersWithCardExpiringBefore(@Param("year") int year, @Param("month") int month);
}
