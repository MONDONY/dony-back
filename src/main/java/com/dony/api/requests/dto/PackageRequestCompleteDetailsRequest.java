package com.dony.api.requests.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record PackageRequestCompleteDetailsRequest(
    @NotBlank @Size(max = 100) String recipientName,
    @NotBlank @Pattern(regexp = "\\+[1-9]\\d{6,14}") String recipientPhone,
    @Size(max = 100) String recipientCity,
    @NotNull(message = "La valeur déclarée est obligatoire")
    @DecimalMin(value = "0.01", message = "La valeur déclarée doit être positive")
    @DecimalMax(value = "500.00", message = "Valeur maximum : 500 €")
    BigDecimal declaredValueEur
) {}
