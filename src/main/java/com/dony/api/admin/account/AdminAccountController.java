package com.dony.api.admin.account;

import com.dony.api.admin.account.dto.AdminSummary;
import com.dony.api.admin.account.dto.ChangePasswordRequest;
import com.dony.api.admin.account.dto.CreateAdminRequest;
import com.dony.api.admin.account.dto.CredentialsResponse;
import com.dony.api.admin.account.dto.UpdateAdminRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for admin account management (CRUD + password ops).
 *
 * Task 9 — AdminAccountController
 *
 * All endpoints require the ADMIN_MANAGE authority (class-level PreAuthorize),
 * except POST /admin/me/change-password which any authenticated admin can call.
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasAuthority('ADMIN_MANAGE')")
public class AdminAccountController {

    private final AdminAccountService adminAccountService;
    private final AdminUserRepository adminUserRepository;

    public AdminAccountController(AdminAccountService adminAccountService,
                                  AdminUserRepository adminUserRepository) {
        this.adminAccountService = adminAccountService;
        this.adminUserRepository = adminUserRepository;
    }

    // -------------------------------------------------------------------------
    // List admins
    // -------------------------------------------------------------------------

    /**
     * Returns a paginated list of admin accounts.
     * Filterable by role and/or status.
     */
    @GetMapping("/admins")
    public Page<AdminSummary> list(
            @RequestParam(required = false) AdminRole role,
            @RequestParam(required = false) AdminStatus status,
            Pageable pageable) {

        if (role != null && status != null) {
            return adminUserRepository.findByRoleAndStatus(role, status, pageable)
                    .map(AdminSummary::from);
        }
        return adminUserRepository.findAll(pageable).map(AdminSummary::from);
    }

    // -------------------------------------------------------------------------
    // Create admin
    // -------------------------------------------------------------------------

    /**
     * Creates a new admin account.
     * Returns the credentials exactly once (password never stored in plaintext).
     */
    @PostMapping("/admins")
    @ResponseStatus(HttpStatus.CREATED)
    public CredentialsResponse create(@RequestBody CreateAdminRequest req, Authentication auth) {
        UUID actorId = extractActorId(auth);
        return adminAccountService.createAdmin(req, actorId);
    }

    // -------------------------------------------------------------------------
    // Update admin
    // -------------------------------------------------------------------------

    /**
     * Partially updates an admin account (role, status, permissionOverrides, login).
     */
    @PatchMapping("/admins/{id}")
    public AdminSummary update(@PathVariable UUID id,
                               @RequestBody UpdateAdminRequest req,
                               Authentication auth) {
        UUID actorId = extractActorId(auth);
        return AdminSummary.from(adminAccountService.updateAdmin(id, req, actorId));
    }

    // -------------------------------------------------------------------------
    // Reset password (admin-initiated)
    // -------------------------------------------------------------------------

    /**
     * Resets another admin's password and sets mustChangePassword=true.
     */
    @PostMapping("/admins/{id}/reset-password")
    public CredentialsResponse resetPassword(@PathVariable UUID id, Authentication auth) {
        UUID actorId = extractActorId(auth);
        return adminAccountService.resetPassword(id, actorId);
    }

    // -------------------------------------------------------------------------
    // Delete admin (soft-delete)
    // -------------------------------------------------------------------------

    /**
     * Soft-deletes an admin account and disables the Firebase user.
     * Returns 409 if the target is the last active SUPER_ADMIN or if the actor
     * is trying to delete themselves.
     */
    @DeleteMapping("/admins/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication auth) {
        UUID actorId = extractActorId(auth);
        adminAccountService.deleteAdmin(id, actorId);
    }

    // -------------------------------------------------------------------------
    // Self-service: change own password
    // -------------------------------------------------------------------------

    /**
     * Allows any authenticated admin to change their own password.
     * Does NOT require ADMIN_MANAGE — available to all admin roles.
     */
    @PostMapping("/me/change-password")
    @PreAuthorize("isAuthenticated()")
    public void changePassword(@RequestBody ChangePasswordRequest req, Authentication auth) {
        UUID actorId = extractActorId(auth);
        adminAccountService.changeOwnPassword(actorId, req.newPassword(), actorId);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private UUID extractActorId(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof AdminPrincipal p) {
            return p.adminId();
        }
        return null;
    }
}
