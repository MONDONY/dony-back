package com.dony.api.admin;

import com.dony.api.admin.account.AdminPrincipal;
import com.dony.api.admin.users.AdminUserDetailResponse;
import com.dony.api.admin.users.AdminUserFilter;
import com.dony.api.admin.users.AdminUserService;
import com.dony.api.admin.users.AdminUserSummary;
import com.dony.api.auth.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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

    private final AdminUserService adminUserService;
    private final UserService userService;

    public AdminUserController(AdminUserService adminUserService, UserService userService) {
        this.adminUserService = adminUserService;
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public Page<AdminUserSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String kyc,
            @RequestParam(required = false) Boolean pro,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AdminUserFilter filter = new AdminUserFilter(status, role, kyc, pro, city, query);
        return adminUserService.list(filter, PageRequest.of(page, size));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public AdminUserDetailResponse get(@PathVariable UUID userId) {
        return adminUserService.get(userId);
    }

    @PostMapping("/{userId}/suspend")
    @PreAuthorize("hasAuthority('USER_SUSPEND')")
    public AdminUserDetailResponse suspend(
            @PathVariable UUID userId,
            @RequestBody SuspendRequest request,
            Authentication auth) {
        UUID actorId = ((AdminPrincipal) auth.getPrincipal()).adminId();
        return adminUserService.suspend(userId, request.reason(), actorId);
    }

    @PostMapping("/{userId}/ban")
    @PreAuthorize("hasAuthority('USER_BAN')")
    public AdminUserDetailResponse ban(
            @PathVariable UUID userId,
            @RequestBody SuspendRequest request,
            Authentication auth) {
        UUID actorId = ((AdminPrincipal) auth.getPrincipal()).adminId();
        return adminUserService.ban(userId, request.reason(), actorId);
    }

    @PostMapping("/{userId}/unsuspend")
    @PreAuthorize("hasAuthority('USER_SUSPEND')")
    public AdminUserDetailResponse unsuspendUser(
            @PathVariable UUID userId,
            Authentication auth) {
        UUID actorId = ((AdminPrincipal) auth.getPrincipal()).adminId();
        return adminUserService.unsuspend(userId, actorId);
    }

    @PostMapping("/{userId}/suspend-publishing")
    @PreAuthorize("hasAuthority('USER_SUSPEND')")
    public ResponseEntity<Void> suspendPublishing(
            @PathVariable UUID userId,
            @RequestParam(required = false) String reason) {
        userService.suspendPublishing(userId, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/lift-publishing-suspension")
    @PreAuthorize("hasAuthority('USER_SUSPEND')")
    public ResponseEntity<Void> liftPublishingSuspension(@PathVariable UUID userId) {
        userService.liftPublishingSuspension(userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{userId}/commission-rate")
    @PreAuthorize("hasAuthority('USER_COMMISSION')")
    public ResponseEntity<Void> setCommissionRate(
            @PathVariable UUID userId,
            @RequestBody @jakarta.validation.Valid CommissionRateOverrideRequest request) {
        userService.setCommissionRateOverride(userId, request.rate());
        return ResponseEntity.noContent().build();
    }

    public record SuspendRequest(String reason) {}
}
