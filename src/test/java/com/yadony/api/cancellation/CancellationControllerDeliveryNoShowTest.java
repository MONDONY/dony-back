package com.yadony.api.cancellation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CancellationControllerDeliveryNoShowTest {

    @Autowired MockMvc mockMvc;
    @MockBean CancellationService cancellationService;
    @MockBean com.yadony.api.auth.UserRepository userRepository;

    static final UUID BID_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken asRole(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private void stubUser(String uid) {
        com.yadony.api.auth.UserEntity user = new com.yadony.api.auth.UserEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findByFirebaseUid(uid)).thenReturn(java.util.Optional.of(user));
    }

    @Test
    void reportDeliveryNoShow_okForTraveler() throws Exception {
        stubUser("uid-traveler");
        mockMvc.perform(post("/cancellations/bids/{bidId}/report-delivery-noshow", BID_ID)
                        .with(authentication(asRole("uid-traveler", "TRAVELER"))))
                .andExpect(status().isOk());
        verify(cancellationService).reportDeliveryNoShow(eq(BID_ID), any());
    }

    @Test
    void reportDeliveryNoShow_forbiddenForSender() throws Exception {
        mockMvc.perform(post("/cancellations/bids/{bidId}/report-delivery-noshow", BID_ID)
                        .with(authentication(asRole("uid-sender", "SENDER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportTravelerDeliveryNoShow_okForSender() throws Exception {
        stubUser("uid-sender");
        mockMvc.perform(post("/cancellations/bids/{bidId}/report-traveler-delivery-noshow", BID_ID)
                        .with(authentication(asRole("uid-sender", "SENDER"))))
                .andExpect(status().isOk());
        verify(cancellationService).reportTravelerDeliveryNoShow(eq(BID_ID), any());
    }

    @Test
    void reportTravelerDeliveryNoShow_forbiddenForTraveler() throws Exception {
        mockMvc.perform(post("/cancellations/bids/{bidId}/report-traveler-delivery-noshow", BID_ID)
                        .with(authentication(asRole("uid-traveler", "TRAVELER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void contestDeliveryNoShow_okForSender() throws Exception {
        stubUser("uid-sender");
        mockMvc.perform(post("/cancellations/bids/{bidId}/contest-delivery-noshow", BID_ID)
                        .with(authentication(asRole("uid-sender", "SENDER"))))
                .andExpect(status().isOk());
    }

    @Test
    void contestDeliveryNoShow_okForTraveler() throws Exception {
        stubUser("uid-traveler");
        mockMvc.perform(post("/cancellations/bids/{bidId}/contest-delivery-noshow", BID_ID)
                        .with(authentication(asRole("uid-traveler", "TRAVELER"))))
                .andExpect(status().isOk());
    }

    private static UUID eq(UUID v) { return org.mockito.ArgumentMatchers.eq(v); }
    private static UUID any() { return org.mockito.ArgumentMatchers.any(); }
}
