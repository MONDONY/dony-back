package com.dony.api.auth.dto;

import com.dony.api.admin.account.AdminRole;

import java.util.List;

/**
 * Admin information included in UserResponse when the authenticated user
 * is also an admin account.
 *
 * Task 10 — /auth/me enriched with admin data
 */
public record AdminInfo(
        String login,
        AdminRole role,
        List<String> permissions,
        boolean mustChangePassword
) {}
