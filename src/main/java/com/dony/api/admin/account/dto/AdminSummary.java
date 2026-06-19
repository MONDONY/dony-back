package com.dony.api.admin.account.dto;

import com.dony.api.admin.account.AdminRole;
import com.dony.api.admin.account.AdminStatus;
import com.dony.api.admin.account.AdminUserEntity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Summary DTO for an admin account.
 * Never exposes password hashes or Firebase UIDs.
 *
 * Task 9 — AdminAccountController
 */
public record AdminSummary(
        UUID id,
        String login,
        AdminRole role,
        AdminStatus status,
        boolean mustChangePassword,
        Map<String, Boolean> permissionOverrides,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt
) {
    public static AdminSummary from(AdminUserEntity e) {
        return new AdminSummary(
                e.getId(),
                e.getLogin(),
                e.getRole(),
                e.getStatus(),
                Boolean.TRUE.equals(e.getMustChangePassword()),
                e.getPermissionOverrides(),
                e.getLastLoginAt(),
                e.getCreatedAt() != null ? e.getCreatedAt().atOffset(ZoneOffset.UTC) : null
        );
    }
}
