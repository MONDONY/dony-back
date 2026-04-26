package com.dony.api.matching.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefuseParcelRequest(
        @NotBlank @Size(max = 500) String reason,
        String refusalPhotoUrl
) {}
