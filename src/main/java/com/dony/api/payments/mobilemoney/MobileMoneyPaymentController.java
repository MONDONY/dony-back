package com.dony.api.payments.mobilemoney;

import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.payments.mobilemoney.dto.MobileMoneyStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/bids")
public class MobileMoneyPaymentController {

    private final MobileMoneyPaymentService service;
    private final UserRepository userRepository;

    public MobileMoneyPaymentController(MobileMoneyPaymentService service,
                                        UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @PostMapping("/{bidId}/mobile-money/initiate")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<MobileMoneyStatusResponse> initiate(
            @AuthenticationPrincipal String firebaseUid, @PathVariable UUID bidId) {
        UUID callerId = requireCallerId(firebaseUid);
        MobileMoneyPaymentEntity entity = service.initiate(bidId, callerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(entity));
    }

    @GetMapping("/{bidId}/mobile-money/status")
    @PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
    public ResponseEntity<MobileMoneyStatusResponse> getStatus(
            @AuthenticationPrincipal String firebaseUid, @PathVariable UUID bidId) {
        UUID callerId = requireCallerId(firebaseUid);
        return service.getStatus(bidId, callerId)
                .map(e -> ResponseEntity.ok(toResponse(e)))
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "mm-payment-not-found", "Not Found",
                        "Aucun paiement Mobile Money trouvé pour ce bid"));
    }

    // Le principal Spring Security est l'UID Firebase (String), jamais une UserEntity
    // (cf. FirebaseTokenFilter qui pose `uid` comme principal). On résout l'utilisateur
    // via le repository — même pattern que les autres contrôleurs (findByFirebaseUid).
    // L'ancien cast `(UserEntity) getPrincipal()` levait une ClassCastException → 500.
    private UUID requireCallerId(String firebaseUid) {
        if (firebaseUid == null) {
            throw new DonyBusinessException(HttpStatus.UNAUTHORIZED,
                    "unauthenticated", "Unauthenticated", "Authentification requise");
        }
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.UNAUTHORIZED,
                        "unauthenticated", "Unauthenticated", "Authentification requise"))
                .getId();
    }

    private MobileMoneyStatusResponse toResponse(MobileMoneyPaymentEntity e) {
        return new MobileMoneyStatusResponse(
                e.getId(), e.getStatus(), e.getPaymentLink(),
                e.getExpiresAt(), e.getAmount(), e.getCurrency(), e.getFailureReason());
    }
}
