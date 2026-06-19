package com.dony.api.admin.account;

import java.util.UUID;

/**
 * Security principal for authenticated admin users.
 * Stored as the principal of the UsernamePasswordAuthenticationToken
 * set by FirebaseTokenFilter when an admin token is resolved.
 *
 * Task 5 — FirebaseTokenFilter admin integration
 */
public record AdminPrincipal(
        UUID adminId,
        String login,
        AdminRole role,
        boolean mustChangePassword,
        String firebaseUid
) {}
