package com.yadony.api.admin.account.dto;

import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.account.AdminStatus;
import com.yadony.api.admin.account.AdminUserEntity;

import java.util.Map;
import java.util.UUID;

public record AdminProfileResponse(
        UUID id,
        String email,
        AdminRole role,
        AdminStatus status,
        boolean mustChangePassword,
        Map<String, Boolean> permissionOverrides
) {
    public static AdminProfileResponse from(AdminUserEntity e) {
        return new AdminProfileResponse(
                e.getId(),
                e.getEmail(),
                e.getRole(),
                e.getStatus(),
                Boolean.TRUE.equals(e.getMustChangePassword()),
                Map.copyOf(e.getPermissionOverrides())
        );
    }
}
