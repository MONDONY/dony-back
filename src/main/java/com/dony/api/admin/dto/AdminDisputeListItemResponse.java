package com.dony.api.admin.dto;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminDisputeListItemResponse(
    UUID id, UUID bidId, String type, String status,
    String senderName, String travelerName,
    boolean refundFrozen, LocalDateTime createdAt
) {}
