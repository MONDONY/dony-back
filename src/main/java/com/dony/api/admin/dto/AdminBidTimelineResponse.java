package com.dony.api.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AdminBidTimelineResponse(
    UUID bidId,
    List<Entry> entries
) {
    public record Entry(
        LocalDateTime at,
        String kind,
        String label,
        String detail,
        String photoUrl,
        BigDecimal gpsLat,
        BigDecimal gpsLon
    ) {}
}
