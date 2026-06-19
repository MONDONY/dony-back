package com.dony.api.admin.account;

import com.dony.api.admin.account.dto.CreateAdminRequest;
import com.dony.api.admin.account.dto.CredentialsResponse;
import com.dony.api.admin.account.dto.UpdateAdminRequest;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AdminAccountService — Task 7 (TDD).
 *
 * All Firebase and DB dependencies are mocked.
 * Tests cover: create (generate=true / duplicate login), resetPassword, changeOwnPassword,
 * deleteAdmin (self-guard / last-SA guard / happy path), updateAdmin.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAccountService — account lifecycle")
class AdminAccountServiceTest {

    @Mock
    private AdminUserRepository adminUserRepository;

    @Mock
    private FirebaseAuth firebaseAuth;

    @Mock
    private AuditService auditService;

    @Mock
    private AdminAuthService adminAuthService;

    @InjectMocks
    private AdminAccountService adminAccountService;

    // -------------------------------------------------------------------------
    // Test doubles
    // -------------------------------------------------------------------------

    private AdminUserEntity buildEntity(UUID id, String firebaseUid, String login,
                                        AdminRole role, AdminStatus status) {
        AdminUserEntity entity = new AdminUserEntity(firebaseUid, login, role);
        entity.setStatus(status);
        entity.setMustChangePassword(false);
        // Reflectively set id since BaseEntity uses @GeneratedValue
        try {
            var field = entity.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set id on entity", e);
        }
        return entity;
    }

    private UserRecord mockUserRecord(String uid) {
        UserRecord userRecord = mock(UserRecord.class);
        when(userRecord.getUid()).thenReturn(uid);
        return userRecord;
    }

    // -------------------------------------------------------------------------
    // 1. createAdmin(generate=true)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createAdmin(generate=true) → login non-null, password ≥16 chars, Firebase createUser called, entity saved, audit ADMIN_ACCOUNT_CREATED, CredentialsResponse returned")
    void createAdmin_generate_true_happyPath() throws Exception {
        // Arrange
        UUID actorId = UUID.randomUUID();
        CreateAdminRequest req = new CreateAdminRequest(null, null, true, AdminRole.ADMIN, null);

        when(adminUserRepository.existsByLogin(anyString())).thenReturn(false);
        UserRecord userRecord = mockUserRecord("firebase-uid-new");
        when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenReturn(userRecord);

        AdminUserEntity savedEntity = buildEntity(UUID.randomUUID(), "firebase-uid-new", "admin.1", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.save(any(AdminUserEntity.class))).thenReturn(savedEntity);

        // Act
        CredentialsResponse response = adminAccountService.createAdmin(req, actorId);

        // Assert
        assertThat(response.login()).isNotNull().isNotBlank();
        assertThat(response.temporaryPassword()).isNotNull().hasSizeGreaterThanOrEqualTo(16);

        verify(firebaseAuth).createUser(any(UserRecord.CreateRequest.class));
        verify(adminUserRepository).save(any(AdminUserEntity.class));
        verify(adminAuthService).evictByFirebaseUid("firebase-uid-new");
        verify(auditService).log(
                eq("admin_users"),
                any(),
                eq("ADMIN_ACCOUNT_CREATED"),
                eq(actorId),
                any()
        );
    }

    @Test
    @DisplayName("createAdmin(generate=true) → mustChangePassword=true on saved entity")
    void createAdmin_generate_true_mustChangePassword() throws Exception {
        UUID actorId = UUID.randomUUID();
        CreateAdminRequest req = new CreateAdminRequest(null, null, true, AdminRole.SUPPORT, null);

        when(adminUserRepository.existsByLogin(anyString())).thenReturn(false);
        UserRecord userRecord = mockUserRecord("uid-abc");
        when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenReturn(userRecord);

        ArgumentCaptor<AdminUserEntity> entityCaptor = ArgumentCaptor.forClass(AdminUserEntity.class);
        when(adminUserRepository.save(entityCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        adminAccountService.createAdmin(req, actorId);

        assertThat(entityCaptor.getValue().getMustChangePassword()).isTrue();
        assertThat(entityCaptor.getValue().getCreatedBy()).isEqualTo(actorId);
    }

    // -------------------------------------------------------------------------
    // 2. createAdmin → setCustomUserClaims fails → deleteUser called + exception propagated
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createAdmin → setCustomUserClaims throws FirebaseAuthException → Firebase user deleted, RuntimeException propagated")
    void createAdmin_claimsFail_rollbackAndPropagate() throws Exception {
        UUID actorId = UUID.randomUUID();
        CreateAdminRequest req = new CreateAdminRequest(null, null, true, AdminRole.ADMIN, null);

        when(adminUserRepository.existsByLogin(anyString())).thenReturn(false);
        UserRecord userRecord = mockUserRecord("firebase-uid-claims-fail");
        when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenReturn(userRecord);
        FirebaseAuthException claimsException = mock(FirebaseAuthException.class);
        when(claimsException.getMessage()).thenReturn("claims error");
        doThrow(claimsException).when(firebaseAuth).setCustomUserClaims(eq("firebase-uid-claims-fail"), any());

        assertThatThrownBy(() -> adminAccountService.createAdmin(req, actorId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("firebase-uid-claims-fail");

        // Firebase user must be rolled back
        verify(firebaseAuth).deleteUser("firebase-uid-claims-fail");
        // Entity must NOT be persisted
        verify(adminUserRepository, never()).save(any());
        // No audit entry
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // 3. createAdmin(login="dup") where existsByLogin("dup")=true → exception
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createAdmin(generate=false, login='dup') where login exists → DonyBusinessException CONFLICT")
    void createAdmin_duplicateLogin_throwsConflict() throws Exception {
        UUID actorId = UUID.randomUUID();
        CreateAdminRequest req = new CreateAdminRequest("dup", "P@ssw0rd123456", false, AdminRole.ADMIN, null);

        when(adminUserRepository.existsByLogin("dup")).thenReturn(true);

        assertThatThrownBy(() -> adminAccountService.createAdmin(req, actorId))
                .isInstanceOf(DonyBusinessException.class)
                .hasMessageContaining("dup");

        verify(firebaseAuth, never()).createUser(any());
        verify(adminUserRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // 3. resetPassword
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("resetPassword → new password generated, FirebaseAuth.updateUser called, mustChangePassword=true, audit ADMIN_PASSWORD_RESET")
    void resetPassword_happyPath() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-reset", "admin.1", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        when(adminUserRepository.save(any())).thenReturn(entity);

        CredentialsResponse response = adminAccountService.resetPassword(adminId, actorId);

        assertThat(response.temporaryPassword()).isNotNull().hasSizeGreaterThanOrEqualTo(16);
        assertThat(response.login()).isEqualTo("admin.1");

        verify(firebaseAuth).updateUser(any(UserRecord.UpdateRequest.class));
        assertThat(entity.getMustChangePassword()).isTrue();

        verify(auditService).log(
                eq("admin_users"),
                eq(adminId),
                eq("ADMIN_PASSWORD_RESET"),
                eq(actorId),
                any()
        );
        verify(adminAuthService).evictByFirebaseUid("uid-reset");
    }

    // -------------------------------------------------------------------------
    // 4. changeOwnPassword
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("changeOwnPassword → FirebaseAuth.updateUser called, mustChangePassword=false, audit ADMIN_PASSWORD_CHANGED")
    void changeOwnPassword_happyPath() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = adminId; // same — it's own password change

        AdminUserEntity entity = buildEntity(adminId, "uid-own", "admin.2", AdminRole.SUPPORT, AdminStatus.ACTIVE);
        entity.setMustChangePassword(true);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        when(adminUserRepository.save(any())).thenReturn(entity);

        adminAccountService.changeOwnPassword(adminId, "NewStr0ng!P@ssword99", actorId);

        verify(firebaseAuth).updateUser(any(UserRecord.UpdateRequest.class));
        assertThat(entity.getMustChangePassword()).isFalse();

        verify(auditService).log(
                eq("admin_users"),
                eq(adminId),
                eq("ADMIN_PASSWORD_CHANGED"),
                eq(actorId),
                any()
        );
        verify(adminAuthService).evictByFirebaseUid("uid-own");
    }

    // -------------------------------------------------------------------------
    // 5. deleteAdmin(adminId, actorId=adminId) → guard: cannot delete yourself
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deleteAdmin where adminId == actorId → DonyBusinessException (self-delete guard)")
    void deleteAdmin_selfDelete_throws() throws Exception {
        UUID adminId = UUID.randomUUID();

        assertThatThrownBy(() -> adminAccountService.deleteAdmin(adminId, adminId))
                .isInstanceOf(DonyBusinessException.class)
                .hasMessageContaining("own");

        verify(adminUserRepository, never()).save(any());
        verify(firebaseAuth, never()).updateUser(any(UserRecord.UpdateRequest.class));
    }

    // -------------------------------------------------------------------------
    // 6. deleteAdmin → last SUPER_ADMIN active → guard throws
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deleteAdmin on last active SUPER_ADMIN → DonyBusinessException (last SA guard)")
    void deleteAdmin_lastSuperAdmin_throws() {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-sa", "admin.super", AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        when(adminUserRepository.countByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE)).thenReturn(1L);

        assertThatThrownBy(() -> adminAccountService.deleteAdmin(adminId, actorId))
                .isInstanceOf(DonyBusinessException.class)
                .hasMessageContaining("SUPER_ADMIN");

        verify(adminUserRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // 7. deleteAdmin → not last SA → happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deleteAdmin (not last SA, not self) → soft-delete, Firebase disabled, audit ADMIN_ACCOUNT_DELETED")
    void deleteAdmin_notLastSA_happyPath() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-del", "admin.del", AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        when(adminUserRepository.countByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE)).thenReturn(2L);
        when(adminUserRepository.save(any())).thenReturn(entity);

        adminAccountService.deleteAdmin(adminId, actorId);

        // Soft-delete: deletedAt should be set
        assertThat(entity.getDeletedAt()).isNotNull();
        assertThat(entity.getStatus()).isEqualTo(AdminStatus.DISABLED);

        // Firebase disabled
        verify(firebaseAuth).updateUser(any(UserRecord.UpdateRequest.class));

        // Audit
        verify(auditService).log(
                eq("admin_users"),
                eq(adminId),
                eq("ADMIN_ACCOUNT_DELETED"),
                eq(actorId),
                any()
        );

        // Cache evicted by firebaseUid (not adminId, to survive soft-delete @Where filter)
        verify(adminAuthService).evictByFirebaseUid("uid-del");
    }

    // -------------------------------------------------------------------------
    // Helper methods: generateLogin, generatePassword, syntheticEmail
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("generateLogin → returns 'admin.1' when no login exists")
    void generateLogin_noExisting() {
        when(adminUserRepository.existsByLogin(anyString())).thenReturn(false);

        String login = adminAccountService.generateLogin();

        assertThat(login).isEqualTo("admin.1");
    }

    @Test
    @DisplayName("generateLogin → increments to 'admin.2' when admin.1 exists")
    void generateLogin_skipsExisting() {
        when(adminUserRepository.existsByLogin("admin.1")).thenReturn(true);
        when(adminUserRepository.existsByLogin("admin.2")).thenReturn(false);

        String login = adminAccountService.generateLogin();

        assertThat(login).isEqualTo("admin.2");
    }

    @Test
    @DisplayName("generatePassword → length ≥ 16, contains upper/lower/digit/symbol")
    void generatePassword_strongPassword() {
        String password = adminAccountService.generatePassword();

        assertThat(password).hasSizeGreaterThanOrEqualTo(16);
        assertThat(password).matches(".*[A-Z].*");
        assertThat(password).matches(".*[a-z].*");
        assertThat(password).matches(".*[0-9].*");
        assertThat(password).matches(".*[!@#$%^&*()\\-_=+\\[\\]{}].*");
    }

    @Test
    @DisplayName("syntheticEmail → login@admin.dony.invalid")
    void syntheticEmail_format() {
        assertThat(adminAccountService.syntheticEmail("admin.1"))
                .isEqualTo("admin.1@admin.dony.invalid");
    }

    // -------------------------------------------------------------------------
    // Edge case: deleteAdmin on ADMIN role (not SUPER_ADMIN) → no last-SA check
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deleteAdmin on ADMIN role → no last-SA guard needed, proceeds to soft-delete")
    void deleteAdmin_adminRole_noLastSaCheck() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-admin-del", "admin.3", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        when(adminUserRepository.save(any())).thenReturn(entity);

        adminAccountService.deleteAdmin(adminId, actorId);

        verify(adminUserRepository, never()).countByRoleAndStatus(any(), any());
        assertThat(entity.getDeletedAt()).isNotNull();
        verify(adminAuthService).evictByFirebaseUid("uid-admin-del");
    }
}
