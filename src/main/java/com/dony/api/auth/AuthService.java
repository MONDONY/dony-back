package com.dony.api.auth;

import com.dony.api.auth.dto.DeleteImmediatelyRequest;
import com.dony.api.auth.dto.RegisterRequest;
import com.dony.api.auth.dto.UpdateProfileRequest;
import com.dony.api.auth.dto.UpgradeToProRequest;
import com.dony.api.auth.dto.UserResponse;
import com.dony.api.auth.events.UserRegisteredEvent;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.payments.PaymentRepository;
import com.google.firebase.auth.FirebaseToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
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
    private final ApplicationEventPublisher eventPublisher;

    public AuthService(UserRepository userRepository,
                       AuditService auditService,
                       UserService userService,
                       PaymentRepository paymentRepository,
                       AccountFinalizationService accountFinalizationService,
                       ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.userService = userService;
        this.paymentRepository = paymentRepository;
        this.accountFinalizationService = accountFinalizationService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public UserResponse register(String firebaseUid, FirebaseToken decodedToken, RegisterRequest request) {
        // Active user → return as-is
        Optional<UserEntity> active = userRepository.findByFirebaseUid(firebaseUid);
        if (active.isPresent()) {
            return toResponse(active.get());
        }
        // Soft-deleted user with same Firebase UID → reactivate via native UPDATE to bypass
        // @Where filter (em.merge() would SELECT with the filter, find nothing, attempt INSERT → constraint violation)
        Optional<UserEntity> deleted = userRepository.findByFirebaseUidIncludingDeleted(firebaseUid);
        if (deleted.isPresent()) {
            userRepository.reactivateByFirebaseUid(firebaseUid, UserStatus.ACTIVE.name());
            UserEntity reactivated = userRepository.findByFirebaseUid(firebaseUid).orElseThrow();
            reactivated.setRoles(parseRoles(request.roles()));
            // Reset pseudonymized fields (GDPR deletion sets placeholder values)
            reactivated.setFirstName(null);
            reactivated.setLastName(null);
            reactivated.setPhoneNumber(null);
            reactivated.setEmail(null);
            reactivated.setKycStatus(KycStatus.NOT_STARTED);
            // Restore contact info from the re-registration provider
            String signInProvider = null;
            if (decodedToken != null) {
                Object firebaseClaim = decodedToken.getClaims().get("firebase");
                if (firebaseClaim instanceof java.util.Map<?, ?> firebaseMap) {
                    Object provider = firebaseMap.get("sign_in_provider");
                    if (provider instanceof String s) signInProvider = s;
                }
            }
            if ("phone".equals(signInProvider) && request.phoneNumber() != null) {
                reactivated.setPhoneNumber(request.phoneNumber());
            } else if (("google.com".equals(signInProvider) || "apple.com".equals(signInProvider))
                    && decodedToken != null && decodedToken.getEmail() != null) {
                reactivated.setEmail(decodedToken.getEmail());
            } else if ("custom".equals(signInProvider) && request.email() != null) {
                reactivated.setEmail(request.email());
            }
            userRepository.save(reactivated);
            auditService.log("USER", reactivated.getId(), "USER_REACTIVATED", reactivated.getId(),
                    Map.of("firebaseUid", firebaseUid));
            return toResponse(reactivated);
        }
        return createUser(firebaseUid, decodedToken, request);
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
            if (!v.isEmpty() && !v.equals(user.getEmail())) {
                if (userRepository.existsByEmail(v)) {
                    throw new DonyBusinessException(HttpStatus.CONFLICT, "email-already-exists",
                            "Email Already Registered", "Cet email est déjà associé à un compte actif");
                }
                // The email may still be held by a soft-deleted account — free it before claiming it
                userRepository.freeEmailFromDeletedAccounts(v);
                user.setEmail(v);
            } else if (v.isEmpty()) {
                user.setEmail(null);
            }
        }
        if (request.birthDate() != null) {
            user.setBirthDate(request.birthDate());
        }
        if (request.city() != null) {
            String v = request.city().trim();
            user.setCity(v.isEmpty() ? null : v);
        }
        if (request.phoneNumber() != null) {
            String v = request.phoneNumber().trim();
            if (!v.isEmpty() && !v.equals(user.getPhoneNumber())) {
                if (userRepository.existsByPhoneNumber(v)) {
                    throw new DonyBusinessException(HttpStatus.CONFLICT, "phone-already-exists",
                            "Phone Number Already Registered", "Ce numéro est déjà associé à un compte");
                }
                user.setPhoneNumber(v);
            }
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

    @Transactional
    public UserResponse downgradePro(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        if (!user.isProAccount()) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT, "not-pro-account", "Not a PRO account", "Ce compte n'est pas un compte PRO");
        }
        UserEntity updated = userService.downgradePro(user);
        return toResponse(updated);
    }

    private UserResponse createUser(String firebaseUid, FirebaseToken decodedToken, RegisterRequest request) {
        Set<Role> roles = parseRoles(request.roles());

        if (roles.contains(Role.ADMIN)) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "forbidden-role",
                    "Forbidden Role", "Le rôle ADMIN ne peut pas être auto-attribué");
        }

        String signInProvider = null;
        if (decodedToken != null) {
            Object firebaseClaim = decodedToken.getClaims().get("firebase");
            if (firebaseClaim instanceof java.util.Map<?, ?> firebaseMap) {
                Object provider = firebaseMap.get("sign_in_provider");
                if (provider instanceof String s) signInProvider = s;
            }
        }

        UserEntity user = new UserEntity();
        user.setFirebaseUid(firebaseUid);
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.NOT_STARTED);
        user.setRoles(roles);

        switch (signInProvider == null ? "" : signInProvider) {
            case "phone" -> {
                if (request.phoneNumber() == null) {
                    throw new DonyBusinessException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "phone-required",
                            "Phone Required", "Le numéro de téléphone est requis");
                }
                if (userRepository.existsByPhoneNumber(request.phoneNumber())) {
                    throw new DonyBusinessException(
                            HttpStatus.CONFLICT, "phone-already-exists",
                            "Phone Number Already Registered", "Ce numéro est déjà associé à un compte");
                }
                user.setPhoneNumber(request.phoneNumber());
            }
            case "google.com", "apple.com" -> {
                if (decodedToken == null) {
                    throw new DonyBusinessException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "token-required",
                            "Token Required", "Token Firebase manquant");
                }
                String email = decodedToken.getEmail();
                if (email == null) {
                    throw new DonyBusinessException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "email-required",
                            "Email Required", "L'email est introuvable dans le token Firebase");
                }
                if (userRepository.existsByEmail(email)) {
                    return toResponse(userRepository.findByEmail(email).orElseThrow());
                }
                user.setEmail(email);
            }
            case "custom" -> {
                // Pour les custom tokens, l'UID Firebase est l'email utilisé dans createCustomToken(email)
                // On vérifie que l'email du body correspond à l'UID pour éviter l'usurpation
                if (request.email() == null) {
                    throw new DonyBusinessException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "email-required",
                            "Email Required", "L'adresse email est requise");
                }
                if (!firebaseUid.equalsIgnoreCase(request.email())) {
                    throw new DonyBusinessException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "email-mismatch",
                            "Email Mismatch", "L'email fourni ne correspond pas au token Firebase");
                }
                if (userRepository.existsByEmail(request.email())) {
                    return toResponse(userRepository.findByEmail(request.email()).orElseThrow());
                }
                user.setEmail(request.email());
            }
            default -> throw new DonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid-provider",
                    "Invalid Provider", "Mode d'authentification non supporté");
        }

        UserEntity saved = userRepository.save(user);

        auditService.log(
                "USER", saved.getId(), "USER_CREATED", saved.getId(),
                Map.of("provider", String.valueOf(signInProvider), "roles", request.roles())
        );

        eventPublisher.publishEvent(new UserRegisteredEvent(saved.getId(), saved.getFirebaseUid()));

        return toResponse(saved);
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
