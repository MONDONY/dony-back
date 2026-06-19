package com.dony.api.admin.users;

import com.dony.api.auth.UserEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AdminUserDetailResponse(
        UUID id,
        String firstName,
        String lastName,
        String phoneNumber,
        String email,
        String city,
        String country,
        String status,
        String kycStatus,
        boolean isProAccount,
        boolean kiloPro,
        List<String> roles,
        String stripeAccountStatus,
        BigDecimal commissionRateOverride,
        boolean publishingSuspended,
        BigDecimal averageRating,
        int totalTrips,
        int totalShipments,
        int cancellationCount,
        int noShowCount,
        int refusedCount,
        int senderHandoverIncidentCount,
        int ratingCount,
        Instant deletionRequestedAt,
        LocalDateTime createdAt
) {
    public static AdminUserDetailResponse from(UserEntity u) {
        return new AdminUserDetailResponse(
                u.getId(),
                u.getFirstName(),
                u.getLastName(),
                u.getPhoneNumber(),
                u.getEmail(),
                u.getCity(),
                u.getCountry(),
                u.getStatus().name(),
                u.getKycStatus().name(),
                u.isProAccount(),
                u.isKiloPro(),
                u.getRoles().stream().map(Enum::name).sorted().toList(),
                u.getStripeAccountStatus() != null ? u.getStripeAccountStatus().name() : null,
                u.getCommissionRateOverride(),
                u.isPublishingSuspended(),
                u.getAverageRating(),
                u.getTotalTrips(),
                u.getTotalShipments(),
                u.getCancellationCount(),
                u.getNoShowCount(),
                u.getRefusedCount(),
                u.getSenderHandoverIncidentCount(),
                u.getRatingCount(),
                u.getDeletionRequestedAt(),
                u.getCreatedAt()
        );
    }
}
