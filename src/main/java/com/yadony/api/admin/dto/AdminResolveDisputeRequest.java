package com.yadony.api.admin.dto;

public record AdminResolveDisputeRequest(
        String resolution,
        String note
) {}
