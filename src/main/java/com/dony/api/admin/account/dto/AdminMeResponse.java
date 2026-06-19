package com.dony.api.admin.account.dto;

import com.dony.api.admin.account.AdminRole;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO for the authenticated admin's own profile (/admin/me).
 *
 * Task 9 — AdminAccountController
 */
public record AdminMeResponse(
        UUID adminId,
        String login,
        AdminRole role,
        List<String> permissions,
        boolean mustChangePassword
) {}
