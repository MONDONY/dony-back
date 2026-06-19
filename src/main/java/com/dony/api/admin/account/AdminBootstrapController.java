package com.dony.api.admin.account;

import com.dony.api.admin.account.dto.CreateAdminRequest;
import com.dony.api.admin.account.dto.CredentialsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Bootstrap endpoint for initial super-admin creation and break-glass password reset.
 *
 * Task 8 — AdminBootstrapController
 *
 * Two modes:
 * - Create mode: no active SUPER_ADMIN exists → creates one, returns credentials (201)
 * - Break-glass mode: active SUPER_ADMIN exists → resets password (200)
 *
 * Security:
 * - If {@code admin.bootstrap.secret} is blank, the endpoint returns 404 (disabled)
 * - The secret is compared in constant time (MessageDigest.isEqual) to prevent timing attacks
 * - The endpoint is permit-all in SecurityConfig (no Firebase token required)
 */
@RestController
@RequestMapping("/admin/bootstrap")
public class AdminBootstrapController {

    @Value("${dony.admin.bootstrap.secret:}")
    private String bootstrapSecret;

    private final AdminAccountService adminAccountService;
    private final AdminUserRepository adminUserRepository;

    public AdminBootstrapController(AdminAccountService adminAccountService,
                                    AdminUserRepository adminUserRepository) {
        this.adminAccountService = adminAccountService;
        this.adminUserRepository = adminUserRepository;
    }

    /**
     * Bootstrap or break-glass reset of the SUPER_ADMIN account.
     *
     * @param providedSecret value of the {@code X-Bootstrap-Secret} header
     * @return 404 if bootstrap is disabled, 403 if secret is wrong,
     *         201 with credentials if super-admin was created,
     *         200 with credentials if super-admin password was reset
     */
    @PostMapping
    public ResponseEntity<?> bootstrap(
            @RequestHeader(value = "X-Bootstrap-Secret", required = false) String providedSecret) {

        // 1. Bootstrap disabled — return 404
        if (bootstrapSecret == null || bootstrapSecret.isBlank()) {
            return ResponseEntity.notFound().build();
        }

        // 2. Invalid secret — return 403 RFC 7807
        if (!constantTimeEquals(bootstrapSecret, providedSecret)) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                    HttpStatus.FORBIDDEN, "Invalid bootstrap secret");
            pd.setType(URI.create("https://dony.app/errors/forbidden"));
            pd.setTitle("Forbidden");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(pd);
        }

        // 3. Check if an active SUPER_ADMIN already exists
        boolean superAdminExists =
                adminUserRepository.countByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE) > 0;

        if (!superAdminExists) {
            // Create mode: generate new super-admin account
            CreateAdminRequest req = new CreateAdminRequest(
                    null,   // login — auto-generated
                    null,   // password — auto-generated
                    true,   // generate = true
                    AdminRole.SUPER_ADMIN,
                    null    // no permission overrides
            );
            // actorId=null: bootstrap is a system operation, not tied to an admin actor
            CredentialsResponse creds = adminAccountService.createAdmin(req, null);
            return ResponseEntity.status(HttpStatus.CREATED).body(creds);
        } else {
            // Break-glass mode: reset the existing super-admin's password
            AdminUserEntity superAdmin = adminUserRepository
                    .findFirstByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE)
                    .orElseThrow();
            // actorId=null: break-glass is a system operation
            CredentialsResponse creds = adminAccountService.resetPassword(superAdmin.getId(), null);
            return ResponseEntity.ok(creds);
        }
    }

    /**
     * Compares two strings in constant time to prevent timing attacks.
     * Returns false if either argument is null.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
