package com.dony.api.admin.account;

import java.util.EnumSet;
import java.util.Set;

/**
 * Admin roles enum with permission mapping (Task 2).
 * - SUPER_ADMIN: all permissions
 * - ADMIN: all except ADMIN_MANAGE
 * - SUPPORT: limited read + action permissions (15 total)
 */
public enum AdminRole {
    SUPER_ADMIN,
    ADMIN,
    SUPPORT;

    /**
     * Returns the base set of permissions for this role.
     * Overrides are applied separately by AdminPermissions.effective().
     */
    public Set<AdminPermission> permissions() {
        return switch (this) {
            case SUPER_ADMIN -> EnumSet.allOf(AdminPermission.class);

            case ADMIN -> EnumSet.complementOf(EnumSet.of(AdminPermission.ADMIN_MANAGE));

            case SUPPORT -> EnumSet.of(
                    AdminPermission.METRICS_VIEW,
                    AdminPermission.USER_VIEW,
                    AdminPermission.USER_SUSPEND,
                    AdminPermission.USER_BAN,
                    AdminPermission.USER_KYC,
                    AdminPermission.PAYMENT_VIEW,
                    AdminPermission.BID_VIEW,
                    AdminPermission.DISPUTE_VIEW,
                    AdminPermission.ALERT_VIEW,
                    AdminPermission.ALERT_RESOLVE,
                    AdminPermission.MODERATION_VIEW,
                    AdminPermission.MESSAGE_DELETE,
                    AdminPermission.REPORT_VIEW,
                    AdminPermission.REPORT_RESOLVE,
                    AdminPermission.RATING_MODERATE
            );
        };
    }
}
