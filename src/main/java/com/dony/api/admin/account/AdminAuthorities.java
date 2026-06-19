package com.dony.api.admin.account;

import org.springframework.security.core.GrantedAuthority;

import java.util.Set;
import java.util.UUID;

/**
 * Value object representing the resolved authorities for an admin user.
 * Produced by AdminAuthService.resolve() and cached under "adminAuthz".
 *
 * Task 4 — AdminAuthService (authorities + cache)
 *
 * @param role               The admin's role
 * @param authorities        Effective granted authorities (permissions + role authorities)
 * @param mustChangePassword Whether the admin must change their password on next login
 * @param login              The admin's login identifier
 * @param adminId            The admin's UUID (from BaseEntity.getId())
 */
public record AdminAuthorities(
        AdminRole role,
        Set<GrantedAuthority> authorities,
        boolean mustChangePassword,
        String login,
        UUID adminId
) {}
