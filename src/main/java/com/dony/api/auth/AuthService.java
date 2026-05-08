package com.dony.api.auth;

import com.dony.api.auth.dto.DeleteImmediatelyRequest;
import com.dony.api.auth.dto.RegisterRequest;
import com.dony.api.auth.dto.UpdateProfileRequest;
import com.dony.api.auth.dto.UpgradeToProRequest;
import com.dony.api.auth.dto.UserResponse;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.payments.PaymentRepository;
import com.google.firebase.auth.FirebaseToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final UserService userService;
    private final PaymentRepository paymentRepository;
    private final AccountFinalizationService accountFinalizationService;

    public AuthService(UserRepository userRepository,
                       AuditService auditService,
                       UserService userService,
                       PaymentRepository paymentRepository,
                       AccountFinalizationService accountFinalizationService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.userService = userService;
        this.paymentRepository = paymentRepository;
        this.accountFinalizationService = accountFinalizationService;
    }

    @Transactional
    public UserResponse register(String firebaseUid, RegisterRequest request) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(this::toResponse)
                .orElseGet(() -> createUser(firebaseUid, request));
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));
        return toResponse(user);
    }

    @Transactional
    public UserResponse updateProfile(String firebaseUid, UpdateProfileRequest request) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));

        if (request.firstName() != null) {
            String v = request.firstName().trim();
            user.setFirstName(v.isEmpty() ? null : v);
        }
        if (request.lastName() != null) {
            String v = request.lastName().trim();
            user.setLastName(v.isEmpty() ? null : v);
        }
        if (request.email() != null) {
            String v = request.email().trim();
            user.setEmail(v.isEmpty() ? null : v);
        }
        if (request.birthDate() != null) {
            user.setBirthDate(request.birthDate());
        }
        if (request.city() != null) {
            String v = request.city().trim();
            user.setCity(v.isEmpty() ? null : v);
        }

        return toResponse(userRepository.save(user));
    }

    @Transactional
    public void updateFcmToken(String firebaseUid, String fcmToken) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));
        user.setFcmToken(fcmToken);
        userRepository.save(user);
    }

    /**
     * Supprime le compte : soft-delete en DB + suppression dans Firebase Auth.
     */
    @Transactional
    // Story 9.8 — Delegates full GDPR deletion (pseudonymization, KYC cleanup, Firebase revoke)
    public void deleteAccount(String firebaseUid) {
        userService.deleteAccount(firebaseUid);
    }

    /**
     * Suppression immédiate du compte (HARD_IMMEDIATE).
     * Vérifie : statut BANNED → 409, escrow actif → 422, auth_time récent (< 5 min) → 401.
     * Délègue la finalisation RGPD à {@link AccountFinalizationService}.
     */
    @Transactional
    public void deleteImmediately(String firebaseUid, DeleteImmediatelyRequest request) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        if (user.getStatus() == UserStatus.BANNED) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "account-banned",
                    "Conflict", "Ce compte est banni et ne peut pas être supprimé");
        }

        if (paymentRepository.hasActiveEscrowForUser(user.getId())) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "active-transactions",
                    "Unprocessable", "Impossible — vous avez des transactions en cours");
        }

        FirebaseToken decoded = (FirebaseToken) SecurityContextHolder
                .getContext().getAuthentication().getCredentials();
        Object authTimeClaim = decoded.getClaims().get("auth_time");
        if (authTimeClaim == null) {
            throw new DonyBusinessException(HttpStatus.UNAUTHORIZED, "reauth-required",
                    "Re-authentication required",
                    "Veuillez vous ré-authentifier avant de supprimer votre compte définitivement");
        }
        long authTime = ((Number) authTimeClaim).longValue();
        if (Instant.ofEpochSecond(authTime).isBefore(Instant.now().minus(5, ChronoUnit.MINUTES))) {
            throw new DonyBusinessException(HttpStatus.UNAUTHORIZED, "reauth-required",
                    "Re-authentication required",
                    "Veuillez vous ré-authentifier avant de supprimer votre compte définitivement");
        }

        auditService.log("USER", user.getId(), "ACCOUNT_DELETE_IMMEDIATELY_REQUESTED",
                user.getId(), Map.of("initiatedBy", "user"));
        accountFinalizationService.finalize(user, FinalizationReason.HARD_IMMEDIATE);
    }

    /**
     * Réactive un compte supprimé : restore status à ACTIVE et deletedAt à null.
     */
    @Transactional
    public UserResponse reactivateAccount(String firebaseUid) {
        userService.reactivateAccount(firebaseUid);
        return getProfile(firebaseUid);
    }

    /**
     * Upgrades the authenticated user to a PRO account.
     * Delegates business-rule enforcement to {@link UserService#upgradeToPro}.
     *
     * <p>The already-loaded {@link UserEntity} is passed directly to
     * {@code UserService.upgradeToPro} to avoid a redundant DB lookup.
     */
    @Transactional
    public UserResponse upgradeToPro(String firebaseUid, UpgradeToProRequest request) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));
        UserEntity updated = userService.upgradeToPro(user, request);
        return toResponse(updated);
    }

    private UserResponse createUser(String firebaseUid, RegisterRequest request) {
        Set<Role> roles = parseRoles(request.roles());

        if (roles.contains(Role.ADMIN)) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN,
                    "forbidden-role",
                    "Forbidden Role",
                    "Le rôle ADMIN ne peut pas être auto-attribué"
            );
        }

        if (userRepository.existsByPhoneNumber(request.phoneNumber())) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT,
                    "phone-already-exists",
                    "Phone Number Already Registered",
                    "Ce numéro est déjà associé à un compte"
            );
        }

        UserEntity user = new UserEntity();
        user.setFirebaseUid(firebaseUid);
        user.setPhoneNumber(request.phoneNumber());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.NOT_STARTED);
        user.setRoles(roles);

        UserEntity saved = userRepository.save(user);

        auditService.log(
                "USER",
                saved.getId(),
                "USER_CREATED",
                saved.getId(),
                Map.of("phoneHash", hashPhone(request.phoneNumber()), "roles", request.roles())
        );

        return toResponse(saved);
    }

    private static String hashPhone(String phone) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(phone.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return "SHA256:" + hex.substring(0, 16) + "...";
        } catch (NoSuchAlgorithmException e) {
            return "HASHED";
        }
    }

    private Set<Role> parseRoles(Set<String> rawRoles) {
        return rawRoles.stream()
                .map(r -> {
                    try {
                        return Role.valueOf(r.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        throw new DonyBusinessException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "invalid-role",
                                "Invalid Role",
                                "Rôle invalide: " + r + ". Valeurs acceptées: SENDER, TRAVELER"
                        );
                    }
                })
                .collect(Collectors.toSet());
    }

    public UserResponse toResponse(UserEntity user) {
        return new UserResponse(
                user.getId(),
                user.getPhoneNumber(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getBirthDate(),
                user.getCity(),
                user.getRoles().stream().map(Role::name).collect(Collectors.toSet()),
                user.getKycStatus().name(),
                user.getStatus().name(),
                user.getTotalTrips(),
                user.getTotalShipments(),
                user.isProAccount(),
                user.getStripeAccountStatus(),
                user.getCountry()
        );
    }
}
