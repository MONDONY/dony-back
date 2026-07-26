package com.dony.api.auth;

import com.dony.api.auth.dto.UserResponse;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserRoleService {

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final FirebaseContactService firebaseContact;

    public UserRoleService(UserRepository userRepository,
                           AuditService auditService,
                           FirebaseContactService firebaseContact) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.firebaseContact = firebaseContact;
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

        // Rôle universel : tout utilisateur obtient déjà TRAVELER à l'inscription
        // (ou via la migration de backfill V176) — cet endpoint ne fait plus que
        // confirmer/compléter l'état pour les comptes pré-migration, sans prérequis
        // KYC/Stripe (le voyageur cash-only est autorisé, cf. gate conditionnel
        // dans AnnouncementService/BidService).
        user.getRoles().add(Role.TRAVELER);
        userRepository.save(user);

        auditService.log("USER", user.getId(), "USER_ROLE_ADDED", user.getId(),
                Map.of("role", "TRAVELER"));

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
        FirebaseContactService.Contact contact = firebaseContact.getContact(user.getFirebaseUid());
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                contact.phoneNumber(),
                contact.email(),
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
                user.getCountry(),
                user.getBio(),
                user.getLanguages(),
                user.getTransportMode() != null ? user.getTransportMode().name() : null,
                user.getAvatarUrl(),
                user.getAverageRating() != null ? user.getAverageRating().doubleValue() : null,
                null // admin field
        );
    }
}
