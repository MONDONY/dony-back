package com.dony.api.auth;

import com.dony.api.auth.dto.RegisterRequest;
import com.dony.api.auth.dto.UpdateProfileRequest;
import com.dony.api.auth.dto.UserResponse;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final AuditService auditService;

    public AuthService(UserRepository userRepository, AuditService auditService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
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
    public void deleteAccount(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));

        auditService.log(
                "USER",
                user.getId(),
                "ACCOUNT_DELETED",
                user.getId(),
                Map.of("phoneNumber", user.getPhoneNumber() != null ? user.getPhoneNumber() : "")
        );

        // Soft-delete en base (relations masquées via @Where deleted_at IS NULL)
        user.softDelete();
        userRepository.save(user);

        try {
            com.google.firebase.auth.FirebaseAuth.getInstance().deleteUser(firebaseUid);
        } catch (Exception e) {
            throw new DonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "firebase-delete-failed",
                    "Firebase Deletion Failed",
                    "La suppression du compte Firebase a échoué : " + e.getMessage()
            );
        }
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
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(roles);

        UserEntity saved = userRepository.save(user);

        auditService.log(
                "USER",
                saved.getId(),
                "USER_CREATED",
                saved.getId(),
                Map.of("phoneNumber", request.phoneNumber(), "roles", request.roles())
        );

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
                user.getStatus().name()
        );
    }
}
