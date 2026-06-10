package com.dony.api.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.dto.TripsSummaryDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TripsSummaryControllerTest {

    @Mock private TripsSummaryService service;
    @Mock private UserRepository userRepository;

    private TripsSummaryController controller;

    @BeforeEach
    void setUp() {
        controller = new TripsSummaryController(service, userRepository);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("firebase-uid-1", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returns_summary_for_traveler() {
        UserEntity user = travelerUser();
        when(userRepository.findByFirebaseUid("firebase-uid-1")).thenReturn(Optional.of(user));
        when(service.computeSummary(user)).thenReturn(
                new TripsSummaryDto(3, new BigDecimal("19.0"), new BigDecimal("152.46")));

        ResponseEntity<TripsSummaryDto> response = controller.getMyTripsSummary();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().activeTrips()).isEqualTo(3);
        assertThat(response.getBody().kgSoldThisMonth()).isEqualByComparingTo(new BigDecimal("19.0"));
        assertThat(response.getBody().revenueThisMonth()).isEqualByComparingTo(new BigDecimal("152.46"));
    }

    @Test
    void rejects_sender_only_user_with_403() {
        UserEntity user = senderUser();
        when(userRepository.findByFirebaseUid("firebase-uid-1")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> controller.getMyTripsSummary())
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(ex -> assertThat(((DonyBusinessException) ex).getStatus().value()).isEqualTo(403));
    }

    @Test
    void returns_404_when_user_not_found() {
        when(userRepository.findByFirebaseUid("firebase-uid-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getMyTripsSummary())
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(ex -> assertThat(((DonyBusinessException) ex).getStatus().value()).isEqualTo(404));
    }

    @Test
    void returns_401_when_no_authentication() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> controller.getMyTripsSummary())
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(ex -> assertThat(((DonyBusinessException) ex).getStatus().value()).isEqualTo(401));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UserEntity travelerUser() {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setFirebaseUid("firebase-uid-1");
        user.setRoles(Set.of(Role.TRAVELER));
        return user;
    }

    private UserEntity senderUser() {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setFirebaseUid("firebase-uid-1");
        user.setRoles(Set.of(Role.SENDER));
        return user;
    }
}
