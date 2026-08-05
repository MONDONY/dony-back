package com.yadony.api.auth.dto;

import com.yadony.api.admin.account.AdminRole;

import java.util.List;

/**
 * Admin information included in UserResponse when the authenticated user
 * is also an admin account.
 *
 * Task 10 — /auth/me enriched with admin data
 */
public record AdminInfo(
        String email,
        AdminRole role,
        List<String> permissions,
        boolean mustChangePassword
) {}
