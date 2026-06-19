package com.dony.api.admin.account.dto;

import com.dony.api.admin.account.AdminRole;
import com.dony.api.admin.account.AdminStatus;

import java.util.Map;

/**
 * Request DTO for updating an existing admin account.
 * All fields are nullable — only non-null fields are applied.
 */
public record UpdateAdminRequest(
        AdminRole role,
        Map<String, Boolean> permissionOverrides,
        AdminStatus status,
        String login
) {}
