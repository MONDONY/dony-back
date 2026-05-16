package com.dony.api.automation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

// Unified response for both PRESET and CUSTOM rules.
// Fields absent for a given type are excluded via @JsonInclude.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AutomationRuleResponse(
        String id,
        String ruleType,
        boolean enabled,
        // PRESET-only fields
        String label,
        String description,
        Boolean isConfigurable,
        Map<String, Object> config,
        // CUSTOM-only fields
        String name,
        List<Map<String, Object>> conditions,
        Map<String, Object> action,
        String createdAt
) {}
