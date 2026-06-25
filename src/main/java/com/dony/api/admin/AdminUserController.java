package com.dony.api.admin;

import com.dony.api.admin.dto.AdminUserDetailResponse;
import com.dony.api.admin.dto.AdminUserListItemResponse;
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

import java.util.UUID;

@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;
    private final UserRepository userRepository;

    public AdminUserController(UserService userService, UserRepository userRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
    }

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

        return userRepository.findAdminFiltered(
                status != null ? status.name() : null,
                kyc != null ? kyc.name() : null,
                pro,
                normalizedCity,
                normalizedQuery,
                queryLike,
                role != null ? role.name() : null,
                PageRequest.of(page, size)
        ).map(AdminUserListItemResponse::from);
    }

    @GetMapping("/{userId}")
    public AdminUserDetailResponse getUser(@PathVariable UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));
        return AdminUserDetailResponse.from(user);
    }

    @PostMapping("/{userId}/suspend")
    public AdminUserDetailResponse suspendUser(@PathVariable UUID userId,
            @RequestBody SuspendBanRequest request) {
        return AdminUserDetailResponse.from(userService.suspendUser(userId, request.reason()));
    }

    @PostMapping("/{userId}/ban")
    public AdminUserDetailResponse banUser(@PathVariable UUID userId,
            @RequestBody SuspendBanRequest request) {
        return AdminUserDetailResponse.from(userService.banUser(userId, request.reason()));
    }

    @PostMapping("/{userId}/unsuspend")
    public AdminUserDetailResponse unsuspendUser(@PathVariable UUID userId) {
        return AdminUserDetailResponse.from(userService.unsuspendUser(userId));
    }

    @PostMapping("/{userId}/suspend-publishing")
    public ResponseEntity<Void> suspendPublishing(@PathVariable UUID userId,
            @RequestParam(required = false) String reason) {
        userService.suspendPublishing(userId, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/lift-publishing-suspension")
    public ResponseEntity<Void> liftPublishingSuspension(@PathVariable UUID userId) {
        userService.liftPublishingSuspension(userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{userId}/commission-rate")
    public ResponseEntity<Void> setCommissionRate(
            @PathVariable UUID userId,
            @RequestBody @jakarta.validation.Valid CommissionRateOverrideRequest request) {
        userService.setCommissionRateOverride(userId, request.rate());
        return ResponseEntity.noContent().build();
    }

    record SuspendBanRequest(String reason) {}
}
