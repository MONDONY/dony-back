package com.dony.api.automation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AutomationHistoryResponse(
        String id,
        String triggeredAt,
        String ruleId,
        String ruleLabel,
        String bidId,
        String tripId,
        String actionTaken,
        String result
) {}
