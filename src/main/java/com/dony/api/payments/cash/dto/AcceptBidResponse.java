package com.dony.api.payments.cash.dto;

public record AcceptBidResponse(
        AcceptanceStatusDto status,
        String clientSecret,
        String paymentIntentId,
        String error
) {
    public static AcceptBidResponse accepted() {
        return new AcceptBidResponse(AcceptanceStatusDto.ACCEPTED, null, null, null);
    }

    public static AcceptBidResponse requires3ds(String clientSecret, String paymentIntentId) {
        return new AcceptBidResponse(AcceptanceStatusDto.REQUIRES_3DS, clientSecret, paymentIntentId, null);
    }

    public static AcceptBidResponse failed(String error) {
        return new AcceptBidResponse(AcceptanceStatusDto.FAILED, null, null, error);
    }
}
