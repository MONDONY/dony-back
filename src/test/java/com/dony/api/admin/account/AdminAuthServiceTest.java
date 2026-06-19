package com.dony.api.admin.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AdminAuthService — Task 4 (TDD).
 *
 * Covers:
 * - SUPPORT role → ROLE_ADMIN + PAYMENT_VIEW present, PAYMENT_REFUND absent
 * - SUPER_ADMIN role → ROLE_ADMIN + ROLE_SUPER_ADMIN + ADMIN_MANAGE present
 * - DISABLED account → Optional.empty()
 * - Unknown firebaseUid → Optional.empty()
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAuthService — authority resolution")
class AdminAuthServiceTest {

    @Mock
    private AdminUserRepository adminUserRepository;

    @InjectMocks
    private AdminAuthService adminAuthService;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Build a minimal AdminUserEntity without going through JPA. */
    private AdminUserEntity buildEntity(String firebaseUid, String login,
                                        AdminRole role, AdminStatus status) {
        AdminUserEntity entity = new AdminUserEntity(firebaseUid, login, role);
        entity.setStatus(status);
        entity.setMustChangePassword(false);
        return entity;
    }

    private Set<String> authorityNames(AdminAuthorities auth) {
        return auth.authorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    // -------------------------------------------------------------------------
    // SUPPORT tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("SUPPORT (no overrides) → Optional present, ROLE_ADMIN + PAYMENT_VIEW, no PAYMENT_REFUND")
    void testSupport_hasRoleAdminAndPaymentView_notPaymentRefund() {
        AdminUserEntity entity = buildEntity("uid-support", "support@dony", AdminRole.SUPPORT, AdminStatus.ACTIVE);
        when(adminUserRepository.findByFirebaseUid("uid-support")).thenReturn(Optional.of(entity));

        Optional<AdminAuthorities> result = adminAuthService.resolve("uid-support");

        assertTrue(result.isPresent(), "Should return non-empty for active SUPPORT");

        Set<String> names = authorityNames(result.get());

        assertTrue(names.contains("ROLE_ADMIN"),
                "SUPPORT must have ROLE_ADMIN");
        assertTrue(names.contains(AdminPermission.PAYMENT_VIEW.name()),
                "SUPPORT must have PAYMENT_VIEW");
        assertFalse(names.contains(AdminPermission.PAYMENT_REFUND.name()),
                "SUPPORT must NOT have PAYMENT_REFUND");
        assertFalse(names.contains("ROLE_SUPER_ADMIN"),
                "SUPPORT must NOT have ROLE_SUPER_ADMIN");
    }

    @Test
    @DisplayName("SUPPORT → role and login are correctly populated in AdminAuthorities")
    void testSupport_metadataCorrect() {
        AdminUserEntity entity = buildEntity("uid-support2", "agent01", AdminRole.SUPPORT, AdminStatus.ACTIVE);
        entity.setMustChangePassword(true);
        when(adminUserRepository.findByFirebaseUid("uid-support2")).thenReturn(Optional.of(entity));

        AdminAuthorities auth = adminAuthService.resolve("uid-support2").orElseThrow();

        assertEquals(AdminRole.SUPPORT, auth.role());
        assertEquals("agent01", auth.login());
        assertTrue(auth.mustChangePassword());
    }

    // -------------------------------------------------------------------------
    // SUPER_ADMIN tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("SUPER_ADMIN → ROLE_ADMIN + ROLE_SUPER_ADMIN + ADMIN_MANAGE all present")
    void testSuperAdmin_hasAllRolesAndAdminManage() {
        AdminUserEntity entity = buildEntity("uid-super", "superadmin@dony", AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findByFirebaseUid("uid-super")).thenReturn(Optional.of(entity));

        Optional<AdminAuthorities> result = adminAuthService.resolve("uid-super");

        assertTrue(result.isPresent(), "Should return non-empty for active SUPER_ADMIN");

        Set<String> names = authorityNames(result.get());

        assertTrue(names.contains("ROLE_ADMIN"),
                "SUPER_ADMIN must have ROLE_ADMIN");
        assertTrue(names.contains("ROLE_SUPER_ADMIN"),
                "SUPER_ADMIN must have ROLE_SUPER_ADMIN");
        assertTrue(names.contains(AdminPermission.ADMIN_MANAGE.name()),
                "SUPER_ADMIN must have ADMIN_MANAGE");
    }

    @Test
    @DisplayName("SUPER_ADMIN → all permissions present")
    void testSuperAdmin_hasAllPermissions() {
        AdminUserEntity entity = buildEntity("uid-super2", "root@dony", AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findByFirebaseUid("uid-super2")).thenReturn(Optional.of(entity));

        AdminAuthorities auth = adminAuthService.resolve("uid-super2").orElseThrow();
        Set<String> names = authorityNames(auth);

        for (AdminPermission p : AdminPermission.values()) {
            assertTrue(names.contains(p.name()),
                    "SUPER_ADMIN should have " + p.name());
        }
    }

    // -------------------------------------------------------------------------
    // DISABLED account
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DISABLED account → Optional.empty()")
    void testDisabledAccount_returnsEmpty() {
        AdminUserEntity entity = buildEntity("uid-disabled", "disabled@dony", AdminRole.SUPPORT, AdminStatus.DISABLED);
        when(adminUserRepository.findByFirebaseUid("uid-disabled")).thenReturn(Optional.of(entity));

        Optional<AdminAuthorities> result = adminAuthService.resolve("uid-disabled");

        assertTrue(result.isEmpty(), "DISABLED account should return Optional.empty()");
    }

    // -------------------------------------------------------------------------
    // Unknown firebaseUid
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Unknown firebaseUid → Optional.empty()")
    void testUnknownUid_returnsEmpty() {
        when(adminUserRepository.findByFirebaseUid("uid-unknown")).thenReturn(Optional.empty());

        Optional<AdminAuthorities> result = adminAuthService.resolve("uid-unknown");

        assertTrue(result.isEmpty(), "Unknown firebaseUid should return Optional.empty()");
    }

    // -------------------------------------------------------------------------
    // evict — repository interaction
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("evict() calls repository to resolve firebaseUid for the given adminId")
    void testEvict_resolvesFirebaseUid() {
        java.util.UUID adminId = java.util.UUID.randomUUID();
        AdminUserEntity entity = buildEntity("uid-evict", "evict@dony", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));

        // Call resolveFirebaseUid directly (evict() triggers cache eviction which requires Spring AOP context)
        String uid = adminAuthService.resolveFirebaseUid(adminId);

        assertEquals("uid-evict", uid);
        verify(adminUserRepository).findById(adminId);
    }

    @Test
    @DisplayName("resolveFirebaseUid() returns empty string when admin not found")
    void testResolveFirebaseUid_notFound_returnsEmptyString() {
        java.util.UUID adminId = java.util.UUID.randomUUID();
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.empty());

        String uid = adminAuthService.resolveFirebaseUid(adminId);

        assertEquals("", uid);
    }

    // -------------------------------------------------------------------------
    // ADMIN role sanity check
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ADMIN role → ROLE_ADMIN present, ROLE_SUPER_ADMIN absent, no ADMIN_MANAGE")
    void testAdmin_hasRoleAdminNotSuperAdmin() {
        AdminUserEntity entity = buildEntity("uid-admin", "admin@dony", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findByFirebaseUid("uid-admin")).thenReturn(Optional.of(entity));

        AdminAuthorities auth = adminAuthService.resolve("uid-admin").orElseThrow();
        Set<String> names = authorityNames(auth);

        assertTrue(names.contains("ROLE_ADMIN"));
        assertFalse(names.contains("ROLE_SUPER_ADMIN"));
        assertFalse(names.contains(AdminPermission.ADMIN_MANAGE.name()),
                "ADMIN role must NOT have ADMIN_MANAGE");
    }
}
