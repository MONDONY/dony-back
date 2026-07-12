package com.dony.api.payments.dto;

import jakarta.validation.constraints.NotNull;

/** Corps du PATCH /payments/intents/{id}/save-payment-method. */
public record UpdateSavePaymentMethodRequest(@NotNull Boolean save) {
}
