package com.dony.api.matching.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record InviteRequest(
        @NotNull UUID requestId,
        @NotNull UUID announcementId
) {}
