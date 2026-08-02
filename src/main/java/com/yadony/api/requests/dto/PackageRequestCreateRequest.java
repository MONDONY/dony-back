package com.yadony.api.requests.dto;

import com.yadony.api.payments.cash.PaymentMethod;
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
    // Multi-sélection jointe par virgule côté front — alignée sur BidCheckoutRequest
    // (500, cf. V171__unify_content_categories.sql pour le pourquoi).
    @NotBlank @Size(max = 500) String contentCategory,
    @Size(max = 500) String description,
    // Budget TOTAL (gross) obligatoire ; converti en net au service.
    @DecimalMin("0.0") @DecimalMax("560.0") BigDecimal totalBudgetEur,
    @Size(max = 500) String photoUrl,
    @Size(max = 100) String pickupNeighborhood,
    @Size(max = 100) String deliveryNeighborhood,
    boolean negotiable,
    @NotEmpty Set<PaymentMethod> acceptedPaymentMethods,
    // Clés S3 des photos colis (max 4) — sous package_requests/{senderId}/. Remplace photoUrl.
    @Size(max = 4) List<String> photoKeys,
    // null/false → publication directe (comportement historique) ; true → brouillon.
    // Nullable et non primitif pour que l'absence du champ reste distincte de false.
    Boolean saveAsDraft
) {}
