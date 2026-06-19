package com.dony.api.admin.account;

import com.dony.api.admin.account.dto.CreateAdminRequest;
import com.dony.api.admin.account.dto.CredentialsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code POST /admin/bootstrap} — Task 8.
 *
 * Organised in three nested classes, each with its own property override:
 * <ol>
 *   <li>{@code WhenSecretNotConfigured} — {@code admin.bootstrap.secret} empty (default)</li>
 *   <li>{@code WhenSecretConfigured} — {@code admin.bootstrap.secret=test-bootstrap-secret-123}</li>
 * </ol>
 */
@DisplayName("AdminBootstrapControllerIT — POST /admin/bootstrap")
class AdminBootstrapControllerIT {

    // =========================================================================
    // Nested class 1: secret NOT configured → endpoint returns 404 always
    // =========================================================================

    @Nested
    @DisplayName("When ADMIN_BOOTSTRAP_SECRET is not configured")
    @SpringBootTest
    @ActiveProfiles("test")
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "dony.admin.bootstrap.secret=")
    class WhenSecretNotConfigured {

        @Autowired
        MockMvc mockMvc;

        @MockitoBean
        AdminAccountService adminAccountService;

        @MockitoBean
        AdminUserRepository adminUserRepository;

        @Test
        @DisplayName("POST without header → 404")
        void noSecret_noHeader_returns404() throws Exception {
            mockMvc.perform(post("/admin/bootstrap"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("POST with any header → still 404")
        void noSecret_withHeader_returns404() throws Exception {
            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", "any-value"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Repository and service are never called when bootstrap is disabled")
        void noSecret_servicesNeverCalled() throws Exception {
            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", "whatever"))
                    .andExpect(status().isNotFound());

            verify(adminAccountService, never()).createAdmin(any(), any());
            verify(adminAccountService, never()).resetPassword(any(), any());
            verify(adminUserRepository, never()).countByRoleAndStatus(any(), any());
        }
    }

    // =========================================================================
    // Nested class 2: secret IS configured
    // =========================================================================

    @Nested
    @DisplayName("When ADMIN_BOOTSTRAP_SECRET is configured")
    @SpringBootTest
    @ActiveProfiles("test")
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "dony.admin.bootstrap.secret=test-bootstrap-secret-123")
    class WhenSecretConfigured {

        static final String VALID_SECRET = "test-bootstrap-secret-123";

        @Autowired
        MockMvc mockMvc;

        @Autowired
        ObjectMapper objectMapper;

        @MockitoBean
        AdminAccountService adminAccountService;

        @MockitoBean
        AdminUserRepository adminUserRepository;

        // ── 403 cases ──────────────────────────────────────────────────────────

        @Test
        @DisplayName("POST without X-Bootstrap-Secret header → 403 RFC 7807")
        void missingHeader_returns403() throws Exception {
            mockMvc.perform(post("/admin/bootstrap"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.detail").value("Invalid bootstrap secret"));
        }

        @Test
        @DisplayName("POST with wrong X-Bootstrap-Secret → 403 RFC 7807")
        void wrongHeader_returns403() throws Exception {
            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", "wrong-secret"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.detail").value("Invalid bootstrap secret"));
        }

        @Test
        @DisplayName("403 response has RFC 7807 type URI")
        void wrongHeader_403_hasRfc7807TypeUri() throws Exception {
            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", "bad"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://dony.app/errors/forbidden"));
        }

        @Test
        @DisplayName("Services not called when secret is wrong")
        void wrongSecret_servicesNeverCalled() throws Exception {
            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", "wrong"))
                    .andExpect(status().isForbidden());

            verify(adminAccountService, never()).createAdmin(any(), any());
            verify(adminAccountService, never()).resetPassword(any(), any());
            verify(adminUserRepository, never()).countByRoleAndStatus(any(), any());
        }

        // ── 201 create mode: no super-admin exists ─────────────────────────────

        @Test
        @DisplayName("Secret OK, no super-admin → 201 with login and temporaryPassword")
        void secretOk_noSuperAdmin_returns201WithCredentials() throws Exception {
            when(adminUserRepository.countByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE))
                    .thenReturn(0L);
            when(adminAccountService.createAdmin(any(CreateAdminRequest.class), isNull()))
                    .thenReturn(new CredentialsResponse("admin.1", "Abc123!XYZ@qwerty0987"));

            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", VALID_SECRET))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.login").value("admin.1"))
                    .andExpect(jsonPath("$.temporaryPassword").value("Abc123!XYZ@qwerty0987"));
        }

        @Test
        @DisplayName("Secret OK, no super-admin → createAdmin called with SUPER_ADMIN role, generate=true, actorId=null")
        void secretOk_noSuperAdmin_createAdminCalledCorrectly() throws Exception {
            when(adminUserRepository.countByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE))
                    .thenReturn(0L);
            when(adminAccountService.createAdmin(any(CreateAdminRequest.class), isNull()))
                    .thenReturn(new CredentialsResponse("admin.1", "SomePass123!"));

            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", VALID_SECRET))
                    .andExpect(status().isCreated());

            // Verify createAdmin was called with generate=true and SUPER_ADMIN role
            var captor = org.mockito.ArgumentCaptor.forClass(CreateAdminRequest.class);
            verify(adminAccountService).createAdmin(captor.capture(), isNull());
            CreateAdminRequest captured = captor.getValue();
            org.assertj.core.api.Assertions.assertThat(captured.generate()).isTrue();
            org.assertj.core.api.Assertions.assertThat(captured.role()).isEqualTo(AdminRole.SUPER_ADMIN);
            org.assertj.core.api.Assertions.assertThat(captured.login()).isNull();
            org.assertj.core.api.Assertions.assertThat(captured.password()).isNull();
        }

        // ── 200 break-glass mode: super-admin already exists ───────────────────

        @Test
        @DisplayName("Secret OK, super-admin exists → 200 break-glass with login and temporaryPassword")
        void secretOk_superAdminExists_returns200WithCredentials() throws Exception {
            UUID existingId = UUID.randomUUID();
            AdminUserEntity existing = buildSuperAdmin(existingId);

            when(adminUserRepository.countByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE))
                    .thenReturn(1L);
            when(adminUserRepository.findFirstByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE))
                    .thenReturn(Optional.of(existing));
            when(adminAccountService.resetPassword(eq(existingId), isNull()))
                    .thenReturn(new CredentialsResponse("admin.1", "NewPass456!@XY"));

            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", VALID_SECRET))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.login").value("admin.1"))
                    .andExpect(jsonPath("$.temporaryPassword").value("NewPass456!@XY"));
        }

        @Test
        @DisplayName("Secret OK, super-admin exists → resetPassword called with super-admin id, actorId=null")
        void secretOk_superAdminExists_resetPasswordCalledCorrectly() throws Exception {
            UUID existingId = UUID.randomUUID();
            AdminUserEntity existing = buildSuperAdmin(existingId);

            when(adminUserRepository.countByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE))
                    .thenReturn(1L);
            when(adminUserRepository.findFirstByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE))
                    .thenReturn(Optional.of(existing));
            when(adminAccountService.resetPassword(eq(existingId), isNull()))
                    .thenReturn(new CredentialsResponse("admin.1", "NewPass!"));

            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", VALID_SECRET))
                    .andExpect(status().isOk());

            verify(adminAccountService).resetPassword(eq(existingId), isNull());
            verify(adminAccountService, never()).createAdmin(any(), any());
        }

        // ── Helper ─────────────────────────────────────────────────────────────

        private AdminUserEntity buildSuperAdmin(UUID id) {
            AdminUserEntity entity = new AdminUserEntity("firebase-uid-sa", "admin.1", AdminRole.SUPER_ADMIN);
            entity.setStatus(AdminStatus.ACTIVE);
            entity.setMustChangePassword(false);
            // Set id via reflection (BaseEntity uses UUID field, no setter)
            org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", id);
            return entity;
        }
    }
}
