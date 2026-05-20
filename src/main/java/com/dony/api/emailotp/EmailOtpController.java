package com.dony.api.emailotp;

import com.dony.api.emailotp.dto.EmailOtpSendRequest;
import com.dony.api.emailotp.dto.EmailOtpSendResponse;
import com.dony.api.emailotp.dto.EmailOtpVerifyRequest;
import com.dony.api.emailotp.dto.EmailOtpVerifyResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/email-otp")
public class EmailOtpController {

    private final EmailOtpService emailOtpService;

    public EmailOtpController(EmailOtpService emailOtpService) {
        this.emailOtpService = emailOtpService;
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
}
