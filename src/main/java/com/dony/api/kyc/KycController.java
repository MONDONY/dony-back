package com.dony.api.kyc;

import com.dony.api.kyc.dto.KycSessionResponse;
import com.dony.api.kyc.dto.KycStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/kyc")
public class KycController {

    private final KycService kycService;

    public KycController(KycService kycService) {
        this.kycService = kycService;
    }

    @PostMapping("/session")
    public ResponseEntity<KycSessionResponse> createSession() {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(kycService.createSession(firebaseUid));
    }

    @GetMapping("/status")
    public ResponseEntity<KycStatusResponse> getStatus() {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(kycService.getStatus(firebaseUid));
    }

    // Public endpoint — validated by Stripe signature inside KycService
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            HttpServletRequest request,
            @RequestHeader("Stripe-Signature") String sigHeader
    ) throws IOException {
        String payload = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        kycService.processWebhook(payload, sigHeader);
        return ResponseEntity.ok().build();
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (String) auth.getPrincipal();
    }
}
