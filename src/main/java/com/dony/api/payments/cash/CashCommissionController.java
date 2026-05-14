package com.dony.api.payments.cash;

import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.payments.cash.dto.AcceptBidResponse;
import com.dony.api.payments.cash.dto.CommissionMethodResponse;
import com.dony.api.payments.cash.dto.ConfirmAcceptanceResponse;
import com.dony.api.payments.cash.dto.SetupCommissionMethodResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@PreAuthorize("hasRole('TRAVELER')")
public class CashCommissionController {

    private final CashCommissionService cashCommissionService;
    private final UserRepository userRepository;

    public CashCommissionController(CashCommissionService cashCommissionService,
                                    UserRepository userRepository) {
        this.cashCommissionService = cashCommissionService;
        this.userRepository = userRepository;
    }

    @PostMapping("/traveler/commission-method/setup")
    public ResponseEntity<SetupCommissionMethodResponse> setupMethod() {
        UUID userId = resolveUserId();
        return ResponseEntity.ok(cashCommissionService.setupCommissionMethod(userId));
    }

    @GetMapping("/traveler/commission-method")
    public ResponseEntity<CommissionMethodResponse> getMethod() {
        UUID userId = resolveUserId();
        CommissionMethodResponse resp = cashCommissionService.getCommissionMethod(userId);
        return resp != null ? ResponseEntity.ok(resp) : ResponseEntity.noContent().build();
    }

    @DeleteMapping("/traveler/commission-method")
    public ResponseEntity<Void> detachMethod() {
        UUID userId = resolveUserId();
        cashCommissionService.detachCommissionMethod(userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bids/{bidId}/accept-with-commission")
    public ResponseEntity<AcceptBidResponse> acceptCashBid(@PathVariable UUID bidId) {
        UUID userId = resolveUserId();
        AcceptBidResponse resp = cashCommissionService.acceptCashBid(bidId, userId);
        return switch (resp.status()) {
            case ACCEPTED -> ResponseEntity.ok(resp);
            case REQUIRES_3DS -> ResponseEntity.accepted().body(resp);
            case FAILED -> ResponseEntity.unprocessableEntity().body(resp);
        };
    }

    @PostMapping("/bids/{bidId}/confirm-acceptance")
    public ResponseEntity<ConfirmAcceptanceResponse> confirmAcceptance(@PathVariable UUID bidId) {
        ConfirmAcceptanceResponse resp = cashCommissionService.confirmCommissionAcceptance(bidId);
        return resp.accepted() ? ResponseEntity.ok(resp) : ResponseEntity.unprocessableEntity().body(resp);
    }

    private UUID resolveUserId() {
        String firebaseUid = requireFirebaseUid();
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(u -> u.getId())
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.UNAUTHORIZED,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new DonyBusinessException(HttpStatus.UNAUTHORIZED,
                    "unauthorized", "Unauthorized", "Un token Firebase valide est requis");
        }
        return (String) auth.getPrincipal();
    }
}
