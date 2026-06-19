package com.dony.api.admin.account;

/**
 * Admin permissions enum (Task 2).
 * 25 granular permissions for role-based access control.
 */
public enum AdminPermission {
    // Account management
    ADMIN_MANAGE,

    // Metrics & reporting
    METRICS_VIEW,

    // User management (7 permissions)
    USER_VIEW,
    USER_SUSPEND,
    USER_BAN,
    USER_KYC,
    USER_GDPR_DELETE,
    USER_COMMISSION,

    // Payment management
    PAYMENT_VIEW,
    PAYMENT_RELEASE,
    PAYMENT_REFUND,

    // Bid management
    BID_VIEW,

    // Dispute management
    DISPUTE_VIEW,
    DISPUTE_RESOLVE,

    // Alerts & moderation
    ALERT_VIEW,
    ALERT_RESOLVE,
    MODERATION_VIEW,
    MESSAGE_DELETE,

    // Reporting & ratings
    REPORT_VIEW,
    REPORT_RESOLVE,
    RATING_MODERATE,

    // Content & operations
    PROMO_MANAGE,
    AUDIT_VIEW,
    EXPORT_RUN
}
