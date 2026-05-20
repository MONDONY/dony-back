package com.dony.api.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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
    Optional<UserEntity> findByPhoneNumber(String phoneNumber);

    /**
     * Finds a user by Firebase UID regardless of soft-delete status.
     * Used to detect and reactivate accounts that were previously deleted.
     */
    @Query(value = "SELECT * FROM users WHERE firebase_uid = :firebaseUid LIMIT 1", nativeQuery = true)
    Optional<UserEntity> findByFirebaseUidIncludingDeleted(@Param("firebaseUid") String firebaseUid);

    /**
     * Reactivates a soft-deleted account via native UPDATE, bypassing the @Where filter.
     * Required because em.merge() on a soft-deleted entity would trigger an internal
     * SELECT filtered by @Where(deleted_at IS NULL), find nothing, and attempt an INSERT
     * that violates the unique constraint on firebase_uid.
     */
    // clearAutomatically=true: evicts the stale L1 cache entry after the native UPDATE so that
    // the subsequent findByFirebaseUid loads a fresh entity — without this, Hibernate flushes
    // the cached (deleted) state at commit and overwrites our reactivation.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE users SET deleted_at = NULL, status = :status, updated_at = NOW() WHERE firebase_uid = :firebaseUid", nativeQuery = true)
    void reactivateByFirebaseUid(@Param("firebaseUid") String firebaseUid, @Param("status") String status);

    /**
     * Releases an email address claimed by a soft-deleted account so another account can use it.
     * Needed when a user re-registers via phone and tries to reclaim their old email.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE users SET email = CONCAT('deleted_', CAST(id AS TEXT), '@dony.app'), updated_at = NOW() WHERE email = :email AND deleted_at IS NOT NULL", nativeQuery = true)
    void freeEmailFromDeletedAccounts(@Param("email") String email);

    /**
     * Loads a UserEntity with a pessimistic write lock to prevent concurrent
     * Stripe Connect account creation (race condition guard in createConnectAccount).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.id = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Loads a UserEntity by Firebase UID with a pessimistic write lock.
     * Used in traveler role activation to guard against concurrent upgrades.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.firebaseUid = :uid")
    Optional<UserEntity> findByFirebaseUidForUpdate(@Param("uid") String uid);

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
