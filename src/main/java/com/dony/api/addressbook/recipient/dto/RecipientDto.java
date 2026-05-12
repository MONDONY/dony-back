package com.dony.api.addressbook.recipient.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record RecipientDto(
        UUID id,
        String fullName,
        String relationship,
        String phoneE164,
        String whatsappE164,
        String street,
        String city,
        String country,
        String notes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
