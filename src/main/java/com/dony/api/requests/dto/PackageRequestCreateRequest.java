package com.dony.api.requests.dto;

import com.dony.api.payments.cash.PaymentMethod;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public record PackageRequestCreateRequest(
    @NotBlank @Size(max = 100) String departureCity,
    @NotBlank @Size(max = 100) String arrivalCity,
    @NotNull @FutureOrPresent LocalDate desiredDate,
    @Min(0) @Max(7) int dateToleranceDays,
    @NotNull @DecimalMin("0.5") @DecimalMax("32.0") BigDecimal weightKg,
    @NotBlank @Size(max = 255) String contentCategory,
    @Size(max = 500) String description,
    // Budget TOTAL (gross) saisi par l'expéditeur ; converti en net au service. Requis si !negotiable.
    @DecimalMin("0.0") @DecimalMax("560.0") BigDecimal totalBudgetEur,
    @Size(max = 500) String photoUrl,
    @Size(max = 100) String pickupNeighborhood,
    @Size(max = 100) String deliveryNeighborhood,
    boolean negotiable,
    @NotEmpty Set<PaymentMethod> acceptedPaymentMethods,
    // Clés S3 des photos colis (max 4) — sous package_requests/{senderId}/. Remplace photoUrl.
    @Size(max = 4) List<String> photoKeys
) {}
