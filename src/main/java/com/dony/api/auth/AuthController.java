package com.dony.api.auth;

import com.dony.api.auth.dto.FcmTokenRequest;
import com.dony.api.auth.dto.RegisterRequest;
import com.dony.api.auth.dto.UpdateProfileRequest;
import com.dony.api.auth.dto.UserResponse;
import com.dony.api.common.DonyBusinessException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.register(firebaseUid, request));
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getProfile() {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(authService.getProfile(firebaseUid));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(authService.updateProfile(firebaseUid, request));
    }

    @PutMapping("/me/fcm-token")
    public ResponseEntity<Void> updateFcmToken(@Valid @RequestBody FcmTokenRequest request) {
        String firebaseUid = requireFirebaseUid();
        authService.updateFcmToken(firebaseUid, request.fcmToken());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteAccount() {
        String firebaseUid = requireFirebaseUid();
        authService.deleteAccount(firebaseUid);
        return ResponseEntity.noContent().build();
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new DonyBusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "unauthorized",
                    "Unauthorized",
                    "Un token Firebase valide est requis"
            );
        }
        return (String) auth.getPrincipal();
    }
}
