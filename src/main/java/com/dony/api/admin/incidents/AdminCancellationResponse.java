package com.dony.api.admin.incidents;

import com.dony.api.cancellation.CancellationEntity;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminCancellationResponse(
        UUID id,
        UUID bidId,
        UUID cancelledBy,
        String reason,
        String noShowStatus,
        OffsetDateTime contestationDeadline,
        LocalDateTime createdAt
) {
    public static AdminCancellationResponse from(CancellationEntity c) {
        return new AdminCancellationResponse(
                c.getId(), c.getBidId(), c.getCancelledBy(), c.getReason(),
                c.getNoShowStatus().name(), c.getContestationDeadline(), c.getCreatedAt()
        );
    }
}
