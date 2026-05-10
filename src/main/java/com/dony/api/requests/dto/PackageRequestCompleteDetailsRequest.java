package com.dony.api.requests.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record PackageRequestCompleteDetailsRequest(
    @NotBlank @Size(max = 255) String pickupAddressLabel,
    @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal pickupLat,
    @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal pickupLng,
    @NotBlank @Size(max = 255) String deliveryAddressLabel,
    @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal deliveryLat,
    @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal deliveryLng,
    @NotBlank @Size(max = 100) String recipientName,
    @NotBlank @Pattern(regexp = "\\+[1-9]\\d{6,14}") String recipientPhone,
    @NotNull @DecimalMin("0.0") @DecimalMax("500.0") BigDecimal declaredValueEur,
    @AssertTrue boolean disclaimerSigned
) {}
