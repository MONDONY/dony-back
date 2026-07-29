package com.yadony.api.admin.dto;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminUserListItemResponse(
        UUID id,
        String firstName,
        String lastName,
        String phoneNumber,
        String city,
        String country,
        String status,
        String kycStatus,
        boolean isProAccount,
        BigDecimal averageRating,
        int totalTrips,
        int totalShipments,
        LocalDateTime createdAt
) {
    /** Le téléphone provient de Firebase : il n'est plus stocké en base. */
    public static AdminUserListItemResponse from(UserEntity u, FirebaseContactService.Contact contact) {
        return new AdminUserListItemResponse(
                u.getId(),
                u.getFirstName(),
                u.getLastName(),
                contact.phoneNumber(),
                u.getCity(),
                u.getCountry(),
                u.getStatus().name(),
                u.getKycStatus().name(),
                u.isProAccount(),
                u.getAverageRating(),
                u.getTotalTrips(),
                u.getTotalShipments(),
                u.getCreatedAt()
        );
    }
}
