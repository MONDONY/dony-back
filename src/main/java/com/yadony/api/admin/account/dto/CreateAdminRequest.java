package com.yadony.api.admin.account.dto;

import com.yadony.api.admin.account.AdminRole;

import java.util.Map;

/**
 * Request DTO for creating a new admin account.
 * If generate=true, login and password are auto-generated and the provided values are ignored.
 */
public record CreateAdminRequest(
        String login,
        String password,
        boolean generate,
        AdminRole role,
        Map<String, Boolean> permissionOverrides
) {}
