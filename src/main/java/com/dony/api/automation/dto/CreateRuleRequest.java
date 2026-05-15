package com.dony.api.automation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record CreateRuleRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull List<Map<String, Object>> conditions,
        @NotNull Map<String, Object> action
) {}
