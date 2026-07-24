package com.dony.api.emailotp;

import com.dony.api.auth.AuthService;
import com.dony.api.auth.dto.UserResponse;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.emailotp.dto.EmailOtpAttachRequest;
import com.dony.api.emailotp.dto.EmailOtpSendRequest;
import com.dony.api.emailotp.dto.EmailOtpSendResponse;
import com.dony.api.emailotp.dto.EmailOtpVerifyRequest;
import com.dony.api.emailotp.dto.EmailOtpVerifyResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/email-otp")
public class EmailOtpController {

    private final EmailOtpService emailOtpService;
    private final AuthService authService;

    public EmailOtpController(EmailOtpService emailOtpService, AuthService authService) {
        this.emailOtpService = emailOtpService;
        this.authService = authService;
    }

    @PostMapping("/send")
    public ResponseEntity<EmailOtpSendResponse> send(@Valid @RequestBody EmailOtpSendRequest request) {
        var expiresAt = emailOtpService.sendOtp(request.email());
        return ResponseEntity.ok(new EmailOtpSendResponse(expiresAt));
    }

    @PostMapping("/verify")
    public ResponseEntity<EmailOtpVerifyResponse> verify(@Valid @RequestBody EmailOtpVerifyRequest request) {
        String customToken = emailOtpService.verifyOtp(request.email(), request.code());
        return ResponseEntity.ok(new EmailOtpVerifyResponse(customToken));
    }

    /**
     * Rattache une adresse au compte connecté. Adresse et code voyagent ensemble :
     * le serveur exige la preuve de possession au moment même où il écrit, ce qu'un
     * appel de vérification séparé ne garantissait pas.
     */
    @PostMapping("/attach")
    public ResponseEntity<UserResponse> attach(@Valid @RequestBody EmailOtpAttachRequest request) {
        String firebaseUid = requireFirebaseUid();
        emailOtpService.attachEmailToAccount(firebaseUid, request.email(), request.code());
        return ResponseEntity.ok(authService.getProfile(firebaseUid));
    }

    /**
     * Le préfixe /auth/** est en permitAll côté SecurityConfig : cet endroit est donc
     * le seul à pouvoir exiger l'authentification, comme le fait déjà AuthController.
     */
    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())
                || !(auth.getPrincipal() instanceof String uid)) {
            throw new DonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Unauthorized", "Un token Firebase valide est requis");
        }
        return uid;
    }
}
