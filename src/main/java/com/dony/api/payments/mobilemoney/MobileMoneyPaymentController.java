package com.dony.api.payments.mobilemoney;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.payments.mobilemoney.dto.MobileMoneyStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/bids")
public class MobileMoneyPaymentController {

    private final MobileMoneyPaymentService service;

    public MobileMoneyPaymentController(MobileMoneyPaymentService service) {
        this.service = service;
    }

    @PostMapping("/{bidId}/mobile-money/initiate")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<MobileMoneyStatusResponse> initiate(@PathVariable UUID bidId) {
        MobileMoneyPaymentEntity entity = service.initiate(bidId);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(entity));
    }

    @GetMapping("/{bidId}/mobile-money/status")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<MobileMoneyStatusResponse> getStatus(@PathVariable UUID bidId) {
        return service.getStatus(bidId)
                .map(e -> ResponseEntity.ok(toResponse(e)))
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "mm-payment-not-found", "Not Found",
                        "Aucun paiement Mobile Money trouvé pour ce bid"));
    }

    private MobileMoneyStatusResponse toResponse(MobileMoneyPaymentEntity e) {
        return new MobileMoneyStatusResponse(
                e.getId(), e.getStatus(), e.getPaymentLink(),
                e.getExpiresAt(), e.getAmount(), e.getCurrency(), e.getFailureReason());
    }
}
