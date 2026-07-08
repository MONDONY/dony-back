package com.dony.api.tracking.dto;

import java.time.LocalDateTime;

public record TripScanHistoryEntryDto(
        String donNumber,
        String recipientName,
        String eventType,
        LocalDateTime scannedAt
) {}
