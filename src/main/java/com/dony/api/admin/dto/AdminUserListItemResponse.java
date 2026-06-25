package com.dony.api.admin.dto;

import com.dony.api.auth.UserEntity;

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
    public static AdminUserListItemResponse from(UserEntity u) {
        return new AdminUserListItemResponse(
                u.getId(),
                u.getFirstName(),
                u.getLastName(),
                u.getPhoneNumber(),
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
