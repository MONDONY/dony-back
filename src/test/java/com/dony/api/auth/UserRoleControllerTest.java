package com.dony.api.auth;

import com.dony.api.auth.dto.UserResponse;
import com.dony.api.common.DonyBusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserRoleController — tests unitaires")
class UserRoleControllerTest {

    @Mock private UserRoleService userRoleService;

    @InjectMocks private UserRoleController controller;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("principal valide → délègue à UserRoleService et retourne 200")
    void activateTraveler_validAuth_delegates() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("uid-1", null, List.of()));

        UserResponse fakeResponse = new UserResponse(
                UUID.randomUUID(), null, null, null, null, null, null,
                Set.of("SENDER", "TRAVELER"), "NOT_STARTED", "ACTIVE", 0, 0,
                false, null, "FR", null, null, null);
        when(userRoleService.activateTravelerRole("uid-1")).thenReturn(fakeResponse);

        var response = controller.activateTraveler();

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(fakeResponse);
    }

    @Test
    @DisplayName("principal anonymousUser → 401 unauthorized")
    void activateTraveler_anonymousPrincipal_throws401() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of()));

        assertThatThrownBy(() -> controller.activateTraveler())
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("SecurityContext vide → 401 unauthorized")
    void activateTraveler_noAuth_throws401() {
        // SecurityContext cleared in @AfterEach — nothing set here
        assertThatThrownBy(() -> controller.activateTraveler())
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("deactivateTraveler : principal valide → délègue à UserRoleService et retourne 200")
    void deactivateTraveler_validAuth_delegates() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("uid-1", null, List.of()));

        UserResponse fakeResponse = new UserResponse(
                UUID.randomUUID(), null, null, null, null, null, null,
                Set.of("SENDER"), "NOT_STARTED", "ACTIVE", 0, 0,
                false, null, "FR", null, null, null);
        when(userRoleService.deactivateTravelerRole("uid-1")).thenReturn(fakeResponse);

        var response = controller.deactivateTraveler();

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(fakeResponse);
    }
}
