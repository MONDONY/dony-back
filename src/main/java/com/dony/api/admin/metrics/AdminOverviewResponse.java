package com.dony.api.admin.metrics;

import java.math.BigDecimal;

public record AdminOverviewResponse(
        Users users,
        Announcements announcements,
        Bids bids,
        Gmv gmv,
        Queues queues
) {

    public record Users(
            long total,
            long active,
            long suspended,
            long banned,
            long pendingDeletion,
            long kycVerified,
            long kycPending,
            long pro,
            long newLast7d,
            long newLast30d
    ) {}

    public record Announcements(
            long active,
            long full,
            long inProgress,
            long completed,
            long cancelled
    ) {}

    public record Bids(
            long pending,
            long accepted,
            long inTransit,
            long completed,
            long cancelled,
            long total
    ) {}

    public record Gmv(
            BigDecimal escrowHeld,
            BigDecimal released,
            BigDecimal refunded,
            BigDecimal commission
    ) {}

    public record Queues(
            long openDisputes,
            long pendingNoShows,
            long unresolvedAlerts,
            long pendingKyc,
            long escrowJ48
    ) {}
}
