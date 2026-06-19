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

    // -------------------------------------------------------------------------
    // createAdmin(generate=false, login=null) → ADMIN_LOGIN_REQUIRED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createAdmin(generate=false, login=null) → DonyBusinessException ADMIN_LOGIN_REQUIRED")
    void createAdmin_generateFalse_loginNull_throwsLoginRequired() {
        UUID actorId = UUID.randomUUID();
        CreateAdminRequest req = new CreateAdminRequest(null, "somePassword", false, AdminRole.ADMIN, null);

        assertThatThrownBy(() -> adminAccountService.createAdmin(req, actorId))
                .isInstanceOf(DonyBusinessException.class)
                .extracting(e -> ((DonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_LOGIN_REQUIRED");

        verify(adminUserRepository, never()).existsByLogin(any());
        verify(adminUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("createAdmin(generate=false, login=blank) → DonyBusinessException ADMIN_LOGIN_REQUIRED")
    void createAdmin_generateFalse_loginBlank_throwsLoginRequired() {
        UUID actorId = UUID.randomUUID();
        CreateAdminRequest req = new CreateAdminRequest("  ", "somePassword", false, AdminRole.ADMIN, null);

        assertThatThrownBy(() -> adminAccountService.createAdmin(req, actorId))
                .isInstanceOf(DonyBusinessException.class)
                .extracting(e -> ((DonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_LOGIN_REQUIRED");
    }

    // -------------------------------------------------------------------------
    // createAdmin → Firebase createUser throws FirebaseAuthException → FIREBASE_CREATE_FAILED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createAdmin → Firebase createUser throws FirebaseAuthException → DonyBusinessException FIREBASE_CREATE_FAILED")
    void createAdmin_firebaseCreateUser_throws_firebaseCreateFailed() throws Exception {
        UUID actorId = UUID.randomUUID();
        CreateAdminRequest req = new CreateAdminRequest(null, null, true, AdminRole.ADMIN, null);

        when(adminUserRepository.existsByLogin(anyString())).thenReturn(false);
        FirebaseAuthException ex = mock(FirebaseAuthException.class);
        when(ex.getMessage()).thenReturn("firebase create error");
        when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenThrow(ex);

        assertThatThrownBy(() -> adminAccountService.createAdmin(req, actorId))
                .isInstanceOf(DonyBusinessException.class)
                .extracting(e -> ((DonyBusinessException) e).getErrorCode())
                .isEqualTo("FIREBASE_CREATE_FAILED");

        verify(adminUserRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // resetPassword → Firebase updateUser throws FirebaseAuthException → FIREBASE_UPDATE_FAILED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("resetPassword → Firebase updateUser throws FirebaseAuthException → DonyBusinessException FIREBASE_UPDATE_FAILED")
    void resetPassword_firebaseUpdateUser_throws_firebaseUpdateFailed() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-reset-fail", "admin.reset", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));

        FirebaseAuthException ex = mock(FirebaseAuthException.class);
        when(ex.getMessage()).thenReturn("firebase update error");
        when(firebaseAuth.updateUser(any(UserRecord.UpdateRequest.class))).thenThrow(ex);

        assertThatThrownBy(() -> adminAccountService.resetPassword(adminId, actorId))
                .isInstanceOf(DonyBusinessException.class)
                .extracting(e -> ((DonyBusinessException) e).getErrorCode())
                .isEqualTo("FIREBASE_UPDATE_FAILED");

        verify(adminUserRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // changeOwnPassword → Firebase updateUser throws FirebaseAuthException → FIREBASE_UPDATE_FAILED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("changeOwnPassword → Firebase updateUser throws FirebaseAuthException → DonyBusinessException FIREBASE_UPDATE_FAILED")
    void changeOwnPassword_firebaseUpdateUser_throws_firebaseUpdateFailed() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = adminId;

        AdminUserEntity entity = buildEntity(adminId, "uid-own-fail", "admin.own", AdminRole.SUPPORT, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));

        FirebaseAuthException ex = mock(FirebaseAuthException.class);
        when(ex.getMessage()).thenReturn("firebase change error");
        when(firebaseAuth.updateUser(any(UserRecord.UpdateRequest.class))).thenThrow(ex);

        assertThatThrownBy(() -> adminAccountService.changeOwnPassword(adminId, "NewStr0ng!P@ssword99", actorId))
                .isInstanceOf(DonyBusinessException.class)
                .extracting(e -> ((DonyBusinessException) e).getErrorCode())
                .isEqualTo("FIREBASE_UPDATE_FAILED");

        verify(adminUserRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // updateAdmin → status=DISABLED, actorId == adminId → ADMIN_SELF_DISABLE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateAdmin with status=DISABLED and actorId==adminId → DonyBusinessException ADMIN_SELF_DISABLE")
    void updateAdmin_selfDisable_throws() {
        UUID adminId = UUID.randomUUID();
        UUID actorId = adminId;

        AdminUserEntity entity = buildEntity(adminId, "uid-self-disable", "admin.self", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));

        UpdateAdminRequest req = new UpdateAdminRequest(null, null, AdminStatus.DISABLED, null);

        assertThatThrownBy(() -> adminAccountService.updateAdmin(adminId, req, actorId))
                .isInstanceOf(DonyBusinessException.class)
                .extracting(e -> ((DonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_SELF_DISABLE");

        verify(adminUserRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // updateAdmin → status=DISABLED on last active SUPER_ADMIN → ADMIN_LAST_SUPER_ADMIN
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateAdmin with status=DISABLED on last active SUPER_ADMIN → DonyBusinessException ADMIN_LAST_SUPER_ADMIN")
    void updateAdmin_lastSuperAdmin_throws() {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-last-sa", "admin.superadmin", AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        when(adminUserRepository.countByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE)).thenReturn(1L);

        UpdateAdminRequest req = new UpdateAdminRequest(null, null, AdminStatus.DISABLED, null);

        assertThatThrownBy(() -> adminAccountService.updateAdmin(adminId, req, actorId))
                .isInstanceOf(DonyBusinessException.class)
                .extracting(e -> ((DonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_LAST_SUPER_ADMIN");

        verify(adminUserRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // updateAdmin → new login already taken → ADMIN_LOGIN_DUPLICATE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateAdmin with new login already taken → DonyBusinessException ADMIN_LOGIN_DUPLICATE")
    void updateAdmin_newLoginDuplicate_throws() {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-dup-login", "admin.current", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        when(adminUserRepository.existsByLogin("admin.taken")).thenReturn(true);

        UpdateAdminRequest req = new UpdateAdminRequest(null, null, null, "admin.taken");

        assertThatThrownBy(() -> adminAccountService.updateAdmin(adminId, req, actorId))
                .isInstanceOf(DonyBusinessException.class)
                .extracting(e -> ((DonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_LOGIN_DUPLICATE");

        verify(adminUserRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // updateAdmin → new login + Firebase updateUser throws → FIREBASE_UPDATE_FAILED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateAdmin with new login, Firebase updateUser throws → DonyBusinessException FIREBASE_UPDATE_FAILED")
    void updateAdmin_newLogin_firebaseUpdateUser_throws_firebaseUpdateFailed() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-newlogin-fail", "admin.old", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        when(adminUserRepository.existsByLogin("admin.new")).thenReturn(false);

        FirebaseAuthException ex = mock(FirebaseAuthException.class);
        when(ex.getMessage()).thenReturn("firebase email update error");
        when(firebaseAuth.updateUser(any(UserRecord.UpdateRequest.class))).thenThrow(ex);

        UpdateAdminRequest req = new UpdateAdminRequest(null, null, null, "admin.new");

        assertThatThrownBy(() -> adminAccountService.updateAdmin(adminId, req, actorId))
                .isInstanceOf(DonyBusinessException.class)
                .extracting(e -> ((DonyBusinessException) e).getErrorCode())
                .isEqualTo("FIREBASE_UPDATE_FAILED");

        verify(adminUserRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // updateAdmin → status=ACTIVE → enableFirebaseUser called
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateAdmin with status=ACTIVE → enableFirebaseUser called (Firebase updateUser with disabled=false)")
    void updateAdmin_statusActive_enableFirebaseUser() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-enable", "admin.disabled", AdminRole.ADMIN, AdminStatus.DISABLED);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        when(adminUserRepository.save(any())).thenReturn(entity);

        UpdateAdminRequest req = new UpdateAdminRequest(null, null, AdminStatus.ACTIVE, null);

        adminAccountService.updateAdmin(adminId, req, actorId);

        ArgumentCaptor<UserRecord.UpdateRequest> captor = ArgumentCaptor.forClass(UserRecord.UpdateRequest.class);
        verify(firebaseAuth).updateUser(captor.capture());
        assertThat(entity.getStatus()).isEqualTo(AdminStatus.ACTIVE);
        verify(adminAuthService).evictByFirebaseUid("uid-enable");
    }

    // -------------------------------------------------------------------------
    // updateAdmin → status=ACTIVE → Firebase enableUser throws → FIREBASE_UPDATE_FAILED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateAdmin with status=ACTIVE, Firebase enable throws → DonyBusinessException FIREBASE_UPDATE_FAILED")
    void updateAdmin_statusActive_firebaseEnable_throws_firebaseUpdateFailed() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-enable-fail", "admin.toenable", AdminRole.ADMIN, AdminStatus.DISABLED);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));

        FirebaseAuthException ex = mock(FirebaseAuthException.class);
        when(ex.getMessage()).thenReturn("firebase enable error");
        when(firebaseAuth.updateUser(any(UserRecord.UpdateRequest.class))).thenThrow(ex);

        UpdateAdminRequest req = new UpdateAdminRequest(null, null, AdminStatus.ACTIVE, null);

        assertThatThrownBy(() -> adminAccountService.updateAdmin(adminId, req, actorId))
                .isInstanceOf(DonyBusinessException.class)
                .extracting(e -> ((DonyBusinessException) e).getErrorCode())
                .isEqualTo("FIREBASE_UPDATE_FAILED");

        verify(adminUserRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // updateAdmin → status=DISABLED → disableFirebaseUser throws → FIREBASE_UPDATE_FAILED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateAdmin with status=DISABLED, Firebase disable throws → DonyBusinessException FIREBASE_UPDATE_FAILED")
    void updateAdmin_statusDisabled_firebaseDisable_throws_firebaseUpdateFailed() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-disable-fail", "admin.todisable", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));

        FirebaseAuthException ex = mock(FirebaseAuthException.class);
        when(ex.getMessage()).thenReturn("firebase disable error");
        when(firebaseAuth.updateUser(any(UserRecord.UpdateRequest.class))).thenThrow(ex);

        UpdateAdminRequest req = new UpdateAdminRequest(null, null, AdminStatus.DISABLED, null);

        assertThatThrownBy(() -> adminAccountService.updateAdmin(adminId, req, actorId))
                .isInstanceOf(DonyBusinessException.class)
                .extracting(e -> ((DonyBusinessException) e).getErrorCode())
                .isEqualTo("FIREBASE_UPDATE_FAILED");

        verify(adminUserRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // generateLogin → all admin.1..999 taken → returns admin.<hex>
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("generateLogin → all admin.1..999 taken → returns login with hex suffix")
    void generateLogin_allNumericTaken_returnsHexSuffix() {
        // All "admin.N" for N=1..999 are taken
        when(adminUserRepository.existsByLogin(anyString())).thenReturn(true);

        String login = adminAccountService.generateLogin();

        assertThat(login).startsWith("admin.");
        String suffix = login.substring("admin.".length());
        // Suffix should be a hex string (parseable as long hex) — not a pure integer 1-999
        assertThat(suffix).matches("[0-9a-f]+");
    }

    // -------------------------------------------------------------------------
    // updateAdmin → happy path (change role + permissionOverrides)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateAdmin happy path: change role and permissionOverrides → entity updated, saved, audit ADMIN_ACCOUNT_UPDATED")
    void updateAdmin_changeRoleAndPermissions_happyPath() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-update", "admin.update", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        when(adminUserRepository.save(any())).thenReturn(entity);

        UpdateAdminRequest req = new UpdateAdminRequest(AdminRole.SUPPORT, Map.of("MANAGE_USERS", true), null, null);

        AdminUserEntity result = adminAccountService.updateAdmin(adminId, req, actorId);

        assertThat(result.getRole()).isEqualTo(AdminRole.SUPPORT);
        assertThat(result.getPermissionOverrides()).containsEntry("MANAGE_USERS", true);

        verify(adminUserRepository).save(entity);
        verify(adminAuthService).evictByFirebaseUid("uid-update");
        verify(auditService).log(
                eq("admin_users"),
                eq(adminId),
                eq("ADMIN_ACCOUNT_UPDATED"),
                eq(actorId),
                any()
        );
    }

    // -------------------------------------------------------------------------
    // findOrThrow → admin not found → ADMIN_NOT_FOUND
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("resetPassword on non-existent admin → DonyBusinessException ADMIN_NOT_FOUND")
    void resetPassword_adminNotFound_throws() {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        when(adminUserRepository.findById(adminId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminAccountService.resetPassword(adminId, actorId))
                .isInstanceOf(DonyBusinessException.class)
                .extracting(e -> ((DonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_NOT_FOUND");
    }
}
