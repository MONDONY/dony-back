package com.dony.api.admin.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for AdminUserEntity (Task 1 — RBAC).
 */
@Repository
public interface AdminUserRepository extends JpaRepository<AdminUserEntity, UUID> {

    /**
     * Find admin user by Firebase UID.
     */
    Optional<AdminUserEntity> findByFirebaseUid(String firebaseUid);

    /**
     * Find admin user by login.
     */
    Optional<AdminUserEntity> findByLogin(String login);

    /**
     * Count active admin users by role (excludes soft-deleted).
     */
    long countByRoleAndStatusAndDeletedAtIsNull(String role, String status);
}
