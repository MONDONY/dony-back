package com.dony.api.admin;

import com.dony.api.admin.dto.AdminUserDetailResponse;
import com.dony.api.admin.dto.AdminUserListItemResponse;
import com.dony.api.auth.FirebaseContactService;
import com.dony.api.auth.KycStatus;
import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.auth.UserService;
import com.dony.api.auth.UserStatus;
import com.dony.api.common.DonyBusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final FirebaseContactService firebaseContact;

    public AdminUserController(UserService userService,
                               UserRepository userRepository,
                               FirebaseContactService firebaseContact) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.firebaseContact = firebaseContact;
    }

    @PreAuthorize("hasAuthority('USER_VIEW')")
    @GetMapping
    public Page<AdminUserListItemResponse> listUsers(
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) KycStatus kyc,
            @RequestParam(required = false) Boolean pro,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String normalizedQuery = (query != null && !query.isBlank()) ? query.trim() : null;
        String queryLike = normalizedQuery != null ? "%" + normalizedQuery + "%" : null;
        String normalizedCity = (city != null && !city.isBlank()) ? city.trim() : null;

        Page<UserEntity> users = userRepository.findAdminFiltered(
                status != null ? status.name() : null,
                kyc != null ? kyc.name() : null,
                pro,
                normalizedCity,
                queryLike,
                resolveQueryToFirebaseUid(normalizedQuery),
                role != null ? role.name() : null,
                PageRequest.of(page, size)
        );

        Map<String, FirebaseContactService.Contact> contacts = firebaseContact.getContacts(
                users.getContent().stream().map(UserEntity::getFirebaseUid).toList());
        return users.map(u -> AdminUserListItemResponse.from(
                u, contacts.getOrDefault(u.getFirebaseUid(), FirebaseContactService.Contact.EMPTY)));
    }

    /**
     * Téléphone et email ne sont plus en base : une recherche qui en vise un est résolue
     * par Firebase en UID, puis appariée exactement. Les noms restent en recherche
     * partielle SQL.
     *
     * <p>La forme du terme choisit le lookup, sinon toute recherche par nom, le cas
     * dominant, paierait deux appels réseau voués à échouer avant même que le SQL parte.
     */
    private String resolveQueryToFirebaseUid(String normalizedQuery) {
        if (normalizedQuery == null) {
            return null;
        }
        if (normalizedQuery.contains("@")) {
            return firebaseContact.findUidByEmail(normalizedQuery).orElse(null);
        }
        // Les numéros sont stockés en E.164 côté Firebase, donc préfixés de « + ».
        if (normalizedQuery.startsWith("+")) {
            return firebaseContact.findUidByPhone(normalizedQuery).orElse(null);
        }
        return null;
    }

    @PreAuthorize("hasAuthority('USER_VIEW')")
    @GetMapping("/{userId}")
    public AdminUserDetailResponse getUser(@PathVariable UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));
        return detail(user);
    }

    @PreAuthorize("hasAuthority('USER_SUSPEND')")
    @PostMapping("/{userId}/suspend")
    public AdminUserDetailResponse suspendUser(@PathVariable UUID userId,
            @RequestBody SuspendBanRequest request) {
        return detail(userService.suspendUser(userId, request.reason()));
    }

    @PreAuthorize("hasAuthority('USER_BAN')")
    @PostMapping("/{userId}/ban")
    public AdminUserDetailResponse banUser(@PathVariable UUID userId,
            @RequestBody SuspendBanRequest request) {
        return detail(userService.banUser(userId, request.reason()));
    }

    @PreAuthorize("hasAuthority('USER_SUSPEND')")
    @PostMapping("/{userId}/unsuspend")
    public AdminUserDetailResponse unsuspendUser(@PathVariable UUID userId) {
        return detail(userService.unsuspendUser(userId));
    }

    @PreAuthorize("hasAuthority('USER_SUSPEND')")
    @PostMapping("/{userId}/suspend-publishing")
    public ResponseEntity<Void> suspendPublishing(@PathVariable UUID userId,
            @RequestParam(required = false) String reason) {
        userService.suspendPublishing(userId, reason);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasAuthority('USER_SUSPEND')")
    @PostMapping("/{userId}/lift-publishing-suspension")
    public ResponseEntity<Void> liftPublishingSuspension(@PathVariable UUID userId) {
        userService.liftPublishingSuspension(userId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasAuthority('USER_COMMISSION')")
    @PutMapping("/{userId}/commission-rate")
    public AdminUserDetailResponse setCommissionRate(
            @PathVariable UUID userId,
            @RequestBody @jakarta.validation.Valid CommissionRateOverrideRequest request) {
        return detail(userService.setCommissionRateOverride(userId, request.rate()));
    }

    private AdminUserDetailResponse detail(UserEntity user) {
        return AdminUserDetailResponse.from(user, firebaseContact.getContact(user.getFirebaseUid()));
    }

    record SuspendBanRequest(String reason) {}
}
