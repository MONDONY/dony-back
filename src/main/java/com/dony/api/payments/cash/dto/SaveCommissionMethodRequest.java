package com.dony.api.payments.cash.dto;

import jakarta.validation.constraints.NotBlank;

public record SaveCommissionMethodRequest(@NotBlank String paymentMethodId) {}
