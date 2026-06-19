package com.dony.api.admin.account;

import com.dony.api.admin.account.dto.CreateAdminRequest;
import com.dony.api.admin.account.dto.CredentialsResponse;
import com.dony.api.admin.account.dto.UpdateAdminRequest;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service for admin account lifecycle: create, reset/change password, update, delete.
 *
 * Task 7 — AdminAccountService (Firebase + DB + guards)
 *
 * Guards enforced:
 * - Cannot disable/delete yourself (actorId == adminId)
 * - Cannot disable/delete the last active SUPER_ADMIN
 */
@Service
@Transactional
public class AdminAccountService {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);

    private static final String CHARS_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String CHARS_LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String CHARS_DIGIT = "0123456789";
    private static final String CHARS_SYMBOL = "!@#$%^&*()-_=+[]{}";
    private static final String CHARS_ALL = CHARS_UPPER + CHARS_LOWER + CHARS_DIGIT + CHARS_SYMBOL;
    private static final int PASSWORD_LENGTH = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminUserRepository adminUserRepository;
    private final FirebaseAuth firebaseAuth;
    private final AuditService auditService;
    private final AdminAuthService adminAuthService;

    public AdminAccountService(AdminUserRepository adminUserRepository,
                                FirebaseAuth firebaseAuth,
                                AuditService auditService,
                                AdminAuthService adminAuthService) {
        this.adminUserRepository = adminUserRepository;
        this.firebaseAuth = firebaseAuth;
        this.auditService = auditService;
        this.adminAuthService = adminAuthService;
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    /**
     * Creates a new admin account.
     * If req.generate() == true, login and password are auto-generated.
     * A synthetic email (login@admin.dony.invalid) is used for Firebase.
     * Returns the credentials once — password is never stored in plaintext.
     */
    public CredentialsResponse createAdmin(CreateAdminRequest req, UUID actorId) {
        String login;
        String password;

        if (req.generate()) {
            login = generateLogin();
            password = generatePassword();
        } else {
            login = req.login();
            password = req.password();
            if (login == null || login.isBlank()) {
                throw new DonyBusinessException(
                        HttpStatus.BAD_REQUEST,
                        "ADMIN_LOGIN_REQUIRED",
                        "Login required",
                        "login is required when generate=false"
                );
            }
            if (adminUserRepository.existsByLogin(login)) {
                throw new DonyBusinessException(
                        HttpStatus.CONFLICT,
                        "ADMIN_LOGIN_DUPLICATE",
                        "Login already in use",
                        "An admin account with login '" + login + "' already exists"
                );
            }
        }

        String email = syntheticEmail(login);

        // Create Firebase user
        String firebaseUid;
        try {
            UserRecord.CreateRequest createRequest = new UserRecord.CreateRequest()
                    .setEmail(email)
                    .setPassword(password)
                    .setDisplayName(login);
            UserRecord userRecord = firebaseAuth.createUser(createRequest);
            firebaseUid = userRecord.getUid();
        } catch (FirebaseAuthException e) {
            log.error("Firebase createUser failed for login={}: {}", login, e.getMessage());
            throw new DonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "FIREBASE_CREATE_FAILED",
                    "Firebase account creation failed",
                    e.getMessage()
            );
        }

        // Set custom claim ROLE_ADMIN
        try {
            firebaseAuth.setCustomUserClaims(firebaseUid, Map.of("ROLE_ADMIN", true));
        } catch (FirebaseAuthException e) {
            log.error("Failed to set custom claims for uid={}: {}", firebaseUid, e.getMessage());
            // Non-fatal: account is created, but claim is missing — log and continue
        }

        // Persist admin entity
        AdminUserEntity entity = new AdminUserEntity(firebaseUid, login, req.role());
        entity.setMustChangePassword(true);
        entity.setStatus(AdminStatus.ACTIVE);
        entity.setCreatedBy(actorId);
        if (req.permissionOverrides() != null) {
            entity.setPermissionOverrides(new HashMap<>(req.permissionOverrides()));
        }
        adminUserRepository.save(entity);

        auditService.log(
                "admin_users",
                entity.getId(),
                "ADMIN_ACCOUNT_CREATED",
                actorId,
                Map.of("login", login, "role", req.role().name())
        );

        log.info("Admin account created: login={}, role={}, by={}", login, req.role(), actorId);
        return new CredentialsResponse(login, password);
    }

    // -------------------------------------------------------------------------
    // Reset password (by another admin)
    // -------------------------------------------------------------------------

    /**
     * Resets the password of an existing admin account (called by a super admin).
     * Sets mustChangePassword=true so the account holder must change on next login.
     */
    public CredentialsResponse resetPassword(UUID adminId, UUID actorId) {
        AdminUserEntity entity = findOrThrow(adminId);
        String newPassword = generatePassword();

        try {
            UserRecord.UpdateRequest updateRequest = new UserRecord.UpdateRequest(entity.getFirebaseUid())
                    .setPassword(newPassword);
            firebaseAuth.updateUser(updateRequest);
        } catch (FirebaseAuthException e) {
            log.error("Firebase updateUser (reset) failed for adminId={}: {}", adminId, e.getMessage());
            throw new DonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "FIREBASE_UPDATE_FAILED",
                    "Firebase password reset failed",
                    e.getMessage()
            );
        }

        entity.setMustChangePassword(true);
        adminUserRepository.save(entity);

        adminAuthService.evict(adminId);

        auditService.log(
                "admin_users",
                adminId,
                "ADMIN_PASSWORD_RESET",
                actorId,
                Map.of("login", entity.getLogin())
        );

        return new CredentialsResponse(entity.getLogin(), newPassword);
    }

    // -------------------------------------------------------------------------
    // Change own password
    // -------------------------------------------------------------------------

    /**
     * Allows an admin to change their own password.
     * Current password verification is handled by Firebase re-auth on the front end.
     * Back: just updates Firebase + sets mustChangePassword=false.
     */
    public void changeOwnPassword(UUID adminId, String newPassword, UUID actorId) {
        AdminUserEntity entity = findOrThrow(adminId);

        try {
            UserRecord.UpdateRequest updateRequest = new UserRecord.UpdateRequest(entity.getFirebaseUid())
                    .setPassword(newPassword);
            firebaseAuth.updateUser(updateRequest);
        } catch (FirebaseAuthException e) {
            log.error("Firebase updateUser (changeOwn) failed for adminId={}: {}", adminId, e.getMessage());
            throw new DonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "FIREBASE_UPDATE_FAILED",
                    "Firebase password change failed",
                    e.getMessage()
            );
        }

        entity.setMustChangePassword(false);
        adminUserRepository.save(entity);

        adminAuthService.evict(adminId);

        auditService.log(
                "admin_users",
                adminId,
                "ADMIN_PASSWORD_CHANGED",
                actorId,
                Map.of("login", entity.getLogin())
        );
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    /**
     * Updates role, permission overrides, status, or login of an admin account.
     * Guards: cannot disable/delete the last active SUPER_ADMIN.
     *         Cannot disable yourself.
     */
    public AdminUserEntity updateAdmin(UUID adminId, UpdateAdminRequest req, UUID actorId) {
        AdminUserEntity entity = findOrThrow(adminId);

        // Guard: cannot disable yourself
        if (req.status() == AdminStatus.DISABLED && adminId.equals(actorId)) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT,
                    "ADMIN_SELF_DISABLE",
                    "Cannot disable your own account",
                    "You cannot disable your own admin account"
            );
        }

        // Guard: cannot disable the last active SUPER_ADMIN
        if (req.status() == AdminStatus.DISABLED
                && entity.getRole() == AdminRole.SUPER_ADMIN
                && adminUserRepository.countByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE) <= 1) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT,
                    "ADMIN_LAST_SUPER_ADMIN",
                    "Cannot disable last active SUPER_ADMIN",
                    "There must always be at least one active SUPER_ADMIN"
            );
        }

        if (req.role() != null) {
            entity.setRole(req.role());
        }
        if (req.permissionOverrides() != null) {
            entity.setPermissionOverrides(new HashMap<>(req.permissionOverrides()));
        }
        if (req.status() != null) {
            entity.setStatus(req.status());
            // Sync Firebase disabled state
            if (req.status() == AdminStatus.DISABLED) {
                disableFirebaseUser(entity.getFirebaseUid());
            } else if (req.status() == AdminStatus.ACTIVE) {
                enableFirebaseUser(entity.getFirebaseUid());
            }
        }
        if (req.login() != null && !req.login().equals(entity.getLogin())) {
            if (adminUserRepository.existsByLogin(req.login())) {
                throw new DonyBusinessException(
                        HttpStatus.CONFLICT,
                        "ADMIN_LOGIN_DUPLICATE",
                        "Login already in use",
                        "An admin account with login '" + req.login() + "' already exists"
                );
            }
            // Update synthetic email in Firebase too
            try {
                UserRecord.UpdateRequest updateRequest = new UserRecord.UpdateRequest(entity.getFirebaseUid())
                        .setEmail(syntheticEmail(req.login()));
                firebaseAuth.updateUser(updateRequest);
            } catch (FirebaseAuthException e) {
                log.error("Firebase updateUser (email) failed for adminId={}: {}", adminId, e.getMessage());
                throw new DonyBusinessException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "FIREBASE_UPDATE_FAILED",
                        "Firebase email update failed",
                        e.getMessage()
                );
            }
            entity.setLogin(req.login());
        }

        adminUserRepository.save(entity);
        adminAuthService.evict(adminId);

        auditService.log(
                "admin_users",
                adminId,
                "ADMIN_ACCOUNT_UPDATED",
                actorId,
                Map.of("login", entity.getLogin())
        );

        return entity;
    }

    // -------------------------------------------------------------------------
    // Delete (soft-delete + Firebase disable)
    // -------------------------------------------------------------------------

    /**
     * Soft-deletes an admin account and disables the corresponding Firebase user.
     * Guards: cannot delete yourself; cannot delete the last active SUPER_ADMIN.
     */
    public void deleteAdmin(UUID adminId, UUID actorId) {
        // Guard: cannot delete yourself
        if (adminId.equals(actorId)) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT,
                    "ADMIN_SELF_DELETE",
                    "Cannot delete your own account",
                    "You cannot delete your own admin account"
            );
        }

        AdminUserEntity entity = findOrThrow(adminId);

        // Guard: cannot delete the last active SUPER_ADMIN
        if (entity.getRole() == AdminRole.SUPER_ADMIN
                && adminUserRepository.countByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE) <= 1) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT,
                    "ADMIN_LAST_SUPER_ADMIN",
                    "Cannot delete last active SUPER_ADMIN",
                    "There must always be at least one active SUPER_ADMIN"
            );
        }

        // Disable in Firebase before soft-deleting
        disableFirebaseUser(entity.getFirebaseUid());

        // Soft-delete: use BaseEntity helper
        entity.softDelete();
        entity.setStatus(AdminStatus.DISABLED);
        adminUserRepository.save(entity);

        adminAuthService.evict(adminId);

        auditService.log(
                "admin_users",
                adminId,
                "ADMIN_ACCOUNT_DELETED",
                actorId,
                Map.of("login", entity.getLogin())
        );

        log.info("Admin account soft-deleted: id={}, login={}, by={}", adminId, entity.getLogin(), actorId);
    }

    // -------------------------------------------------------------------------
    // Public helpers (also used by bootstrap)
    // -------------------------------------------------------------------------

    /**
     * Generates a unique login of the form "admin.N" (N=1, 2, ...).
     * If the numeric sequence runs out, falls back to "admin.XXXXXXXX" with a random suffix.
     */
    public String generateLogin() {
        for (int i = 1; i <= 999; i++) {
            String candidate = "admin." + i;
            if (!adminUserRepository.existsByLogin(candidate)) {
                return candidate;
            }
        }
        // Fallback: random suffix
        String suffix = Long.toHexString(RANDOM.nextLong() & 0xFFFFFFFFL);
        return "admin." + suffix;
    }

    /**
     * Generates a strong password of at least 20 characters:
     * at least one uppercase, one lowercase, one digit, one symbol.
     */
    public String generatePassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        // Guarantee at least one of each required character class
        sb.append(CHARS_UPPER.charAt(RANDOM.nextInt(CHARS_UPPER.length())));
        sb.append(CHARS_LOWER.charAt(RANDOM.nextInt(CHARS_LOWER.length())));
        sb.append(CHARS_DIGIT.charAt(RANDOM.nextInt(CHARS_DIGIT.length())));
        sb.append(CHARS_SYMBOL.charAt(RANDOM.nextInt(CHARS_SYMBOL.length())));

        for (int i = 4; i < PASSWORD_LENGTH; i++) {
            sb.append(CHARS_ALL.charAt(RANDOM.nextInt(CHARS_ALL.length())));
        }

        // Shuffle to avoid predictable positions
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }

    /**
     * Returns the synthetic email address used for Firebase authentication.
     */
    public String syntheticEmail(String login) {
        return login + "@admin.dony.invalid";
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private AdminUserEntity findOrThrow(UUID adminId) {
        return adminUserRepository.findById(adminId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "ADMIN_NOT_FOUND",
                        "Admin account not found",
                        "No admin account found with id: " + adminId
                ));
    }

    private void disableFirebaseUser(String firebaseUid) {
        try {
            UserRecord.UpdateRequest updateRequest = new UserRecord.UpdateRequest(firebaseUid)
                    .setDisabled(true);
            firebaseAuth.updateUser(updateRequest);
        } catch (FirebaseAuthException e) {
            log.error("Firebase disable user failed for uid={}: {}", firebaseUid, e.getMessage());
            throw new DonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "FIREBASE_UPDATE_FAILED",
                    "Firebase disable user failed",
                    e.getMessage()
            );
        }
    }

    private void enableFirebaseUser(String firebaseUid) {
        try {
            UserRecord.UpdateRequest updateRequest = new UserRecord.UpdateRequest(firebaseUid)
                    .setDisabled(false);
            firebaseAuth.updateUser(updateRequest);
        } catch (FirebaseAuthException e) {
            log.error("Firebase enable user failed for uid={}: {}", firebaseUid, e.getMessage());
            throw new DonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "FIREBASE_UPDATE_FAILED",
                    "Firebase enable user failed",
                    e.getMessage()
            );
        }
    }
}
