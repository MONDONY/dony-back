package com.dony.api.cancellation.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record CancellationResponse(
        UUID announcementId,
        int affectedBidsCount,
        String reason,
        List<RematchSuggestionDto> rematchSuggestions,
        LocalDateTime cancelledAt
) {}
