package com.dony.api.admin.account;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Utility class for computing effective permissions after applying overrides.
 * Task 2: Manages permission override logic for admin users.
 *
 * Rules:
 * - SUPER_ADMIN ignores all overrides (always has all permissions)
 * - Other roles: apply overrides (grant if true, revoke if false)
 * - Invalid permission names are silently ignored
 * - null overrides map is safe (treated as no overrides)
 */
public final class AdminPermissions {

    private AdminPermissions() {
        // Utility class, no instantiation
    }

    /**
     * Computes the effective set of permissions for an admin user,
     * starting from role base permissions and applying any overrides.
     *
     * @param role      The admin role
     * @param overrides Map of permission name → boolean (true=grant, false=revoke)
     * @return Set of effective permissions
     */
    public static Set<AdminPermission> effective(AdminRole role, Map<String, Boolean> overrides) {
        EnumSet<AdminPermission> set = EnumSet.copyOf(role.permissions());

        // SUPER_ADMIN ignores all overrides
        if (role == AdminRole.SUPER_ADMIN) {
            return set;
        }

        // Apply overrides for other roles
        if (overrides != null) {
            overrides.forEach((k, v) -> {
                AdminPermission p = parse(k);
                if (p == null) return; // Invalid permission name, ignore
                if (Boolean.TRUE.equals(v)) {
                    set.add(p);
                } else {
                    set.remove(p);
                }
            });
        }

        return set;
    }

    /**
     * Safely parses a permission name string to AdminPermission enum.
     * Returns null if the name is invalid.
     */
    private static AdminPermission parse(String k) {
        try {
            return AdminPermission.valueOf(k);
        } catch (Exception e) {
            return null;
        }
    }
}
