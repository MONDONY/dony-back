package com.dony.api.admin.dto;

public record AdminResolveDisputeRequest(
        String resolution,
        String note
) {}
