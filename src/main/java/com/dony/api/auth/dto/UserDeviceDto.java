package com.dony.api.auth.dto;

import java.time.OffsetDateTime;

public record UserDeviceDto(
        String deviceId,
        String deviceName,
        String platform,
        OffsetDateTime lastSeenAt,
        boolean isCurrent
) {}
