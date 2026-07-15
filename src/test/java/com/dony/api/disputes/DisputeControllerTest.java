package com.dony.api.disputes;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.disputes.dto.DisputeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DisputeControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean DisputeService disputeService;
    @MockBean UserRepository userRepository;

    private static final String TRAVELER_UID = "uid-traveler-disputes";
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken asRole(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static DisputeResponse sample() {
        return new DisputeResponse(UUID.randomUUID(), UUID.randomUUID(),
                "SENDER_NO_SHOW_CONTESTED", "OPEN", true, LocalDateTime.now(),
                "SENDER", "Awa K.", "Lyon", "Abidjan", "FR", "CI",
                java.time.LocalDate.of(2026, 6, 20), new java.math.BigDecimal("5.00"),
                null, null, null, null, false);
    }

    @Test
    void getMyDisputes_okForTraveler() throws Exception {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", TRAVELER_ID);
        when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(user));
        when(disputeService.getDisputesForUser(TRAVELER_ID)).thenReturn(List.of(sample()));

        mockMvc.perform(get("/disputes/me").with(authentication(asRole(TRAVELER_UID, "TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].myRole").value("SENDER"))
                .andExpect(jsonPath("$[0].departureCity").value("Lyon"))
                .andExpect(jsonPath("$[0].isBeneficiary").value(false));
    }

    @Test
    void getMyDisputes_okForSender() throws Exception {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", TRAVELER_ID);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));
        when(disputeService.getDisputesForUser(TRAVELER_ID)).thenReturn(List.of());

        mockMvc.perform(get("/disputes/me").with(authentication(asRole("uid-sender", "SENDER"))))
                .andExpect(status().isOk());
    }
}
