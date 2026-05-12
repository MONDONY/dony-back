package com.dony.api.addressbook.recipient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateRecipientRequest(
        @NotBlank @Size(max = 100) String fullName,
        @Size(max = 50) String relationship,
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "Phone must be in E.164 format") String phoneE164,
        @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "WhatsApp must be in E.164 format") String whatsappE164,
        @Size(max = 255) String street,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Pattern(regexp = "^(SN|CI|ML|CM)$", message = "Country must be one of: SN, CI, ML, CM") String country,
        String notes
) {}
