package com.dony.api.admin.account;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for AdminUserEntity (Task 3 — RBAC).
 * Soft-delete filtering is handled by @Where clause on AdminUserEntity.
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
     * Check if an admin user with the given login exists.
     */
    boolean existsByLogin(String login);

    /**
     * Count admin users by role and status (soft-deleted are excluded by @Where).
     */
    long countByRoleAndStatus(AdminRole role, AdminStatus status);

    /**
     * Find admin users by role and status with pagination (soft-deleted are excluded by @Where).
     */
    Page<AdminUserEntity> findByRoleAndStatus(AdminRole role, AdminStatus status, Pageable pageable);

    /**
     * Find admin users by role with pagination (soft-deleted are excluded by @Where).
     */
    Page<AdminUserEntity> findByRole(AdminRole role, Pageable pageable);

    /**
     * Find admin users by status with pagination (soft-deleted are excluded by @Where).
     */
    Page<AdminUserEntity> findByStatus(AdminStatus status, Pageable pageable);

    /**
     * Find the first admin user matching the given role and status (used by break-glass bootstrap).
     * Soft-deleted entities are excluded by @Where clause on AdminUserEntity.
     */
    Optional<AdminUserEntity> findFirstByRoleAndStatus(AdminRole role, AdminStatus status);
}
