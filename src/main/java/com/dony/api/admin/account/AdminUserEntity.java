package com.dony.api.admin.account;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Admin user account entity for RBAC (Task 1).
 * Extends BaseEntity for id, created_at, updated_at, deleted_at (soft delete).
 */
@Entity
@Table(name = "admin_users")
public class AdminUserEntity extends BaseEntity {

    @Column(name = "firebase_uid", nullable = false, unique = true, length = 128)
    private String firebaseUid;

    @Column(name = "login", nullable = false, unique = true, length = 64)
    private String login;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "must_change_password", nullable = false)
    private Boolean mustChangePassword;

    @Convert(converter = PermissionOverridesConverter.class)
    @Column(name = "permission_overrides", nullable = false)
    private Map<String, Boolean> permissionOverrides = new HashMap<>();

    @Column(name = "created_by")
    private java.util.UUID createdBy;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    // Constructors
    public AdminUserEntity() {
        this.mustChangePassword = true;
        this.permissionOverrides = new HashMap<>();
        this.status = "ACTIVE";
    }

    public AdminUserEntity(String firebaseUid, String login, String role) {
        this();
        this.firebaseUid = firebaseUid;
        this.login = login;
        this.role = role;
    }

    // Getters & setters
    public String getFirebaseUid() { return firebaseUid; }
    public void setFirebaseUid(String firebaseUid) { this.firebaseUid = firebaseUid; }

    public String getLogin() { return login; }
    public void setLogin(String login) { this.login = login; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Boolean getMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(Boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    public Map<String, Boolean> getPermissionOverrides() { return permissionOverrides; }
    public void setPermissionOverrides(Map<String, Boolean> permissionOverrides) { this.permissionOverrides = permissionOverrides; }

    public java.util.UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(java.util.UUID createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(OffsetDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
