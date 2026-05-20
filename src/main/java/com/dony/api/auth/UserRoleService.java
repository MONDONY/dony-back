package com.dony.api.auth;

import com.dony.api.auth.dto.UserResponse;
import com.dony.api.auth.events.UserBecameTravelerEvent;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.auth.KycStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserRoleService {

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public UserRoleService(UserRepository userRepository,
                           AuditService auditService,
                           ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public UserResponse activateTravelerRole(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found",
                        "User Not Found", "Utilisateur introuvable"));

        if (user.getRoles().contains(Role.TRAVELER)) {
            return toResponse(user);
        }

        List<String> missing = new ArrayList<>();
        if (user.getKycStatus() != KycStatus.VERIFIED) {
            missing.add("KYC_NOT_VERIFIED");
        }
        if (user.getStripeAccountStatus() != StripeAccountStatus.ONBOARDING_COMPLETE) {
            missing.add("STRIPE_ACCOUNT_NOT_COMPLETE");
        }

        if (!missing.isEmpty()) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT,
                    "traveler-upgrade-requirements-missing",
                    "Requirements Missing",
                    "Pré-requis manquants pour devenir transporteur",
                    Map.of("missingRequirements", missing));
        }

        user.getRoles().add(Role.TRAVELER);
        userRepository.save(user);

        auditService.log("USER", user.getId(), "USER_ROLE_ADDED", user.getId(),
                Map.of("role", "TRAVELER"));
        eventPublisher.publishEvent(new UserBecameTravelerEvent(user.getId()));

        return toResponse(user);
    }

    @Transactional
    public UserResponse deactivateTravelerRole(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found",
                        "User Not Found", "Utilisateur introuvable"));

        if (!user.getRoles().contains(Role.TRAVELER)) {
            return toResponse(user);
        }

        user.getRoles().remove(Role.TRAVELER);
        userRepository.save(user);

        auditService.log("USER", user.getId(), "USER_ROLE_REMOVED", user.getId(),
                Map.of("role", "TRAVELER", "reason", "user_self_deactivated"));

        return toResponse(user);
    }

    private UserResponse toResponse(UserEntity user) {
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
