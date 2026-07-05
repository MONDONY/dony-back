package com.dony.api.admin.dto;

import com.dony.api.auth.UserEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AdminUserDetailResponse(
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
        LocalDateTime createdAt,
        String email,
        List<String> roles,
        String stripeAccountStatus,
        BigDecimal commissionRateOverride,
        boolean publishingSuspended,
        boolean kiloPro,
        int cancellationCount,
        int noShowCount,
        int refusedCount,
        int senderHandoverIncidentCount,
        int ratingCount,
        LocalDateTime deletionRequestedAt
) {
    public static AdminUserDetailResponse from(UserEntity u) {
        return new AdminUserDetailResponse(
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
                u.getCreatedAt(),
                u.getEmail(),
                u.getRoles().stream().map(Enum::name).toList(),
                u.getStripeAccountStatus() != null ? u.getStripeAccountStatus().name() : null,
                u.getCommissionRateOverride(),
                u.isPublishingSuspended(),
                u.isKiloPro(),
                u.getCancellationCount(),
                u.getNoShowCount(),
                u.getRefusedCount(),
                u.getSenderHandoverIncidentCount(),
                u.getRatingCount(),
                u.getDeletionRequestedAt() != null
                        ? java.time.LocalDateTime.ofInstant(u.getDeletionRequestedAt(), java.time.ZoneOffset.UTC)
                        : null
        );
    }
}
