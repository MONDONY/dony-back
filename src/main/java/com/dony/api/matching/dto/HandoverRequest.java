package com.dony.api.matching.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record HandoverRequest(
        @NotBlank String location,
        @NotNull LocalDateTime windowStart,
        @NotNull LocalDateTime windowEnd
) {}
