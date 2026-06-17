package com.dony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BidPhotoControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private BidService bidService;
    @MockitoBean private BidCheckoutService bidCheckoutService;
    @MockitoBean private com.dony.api.payments.PaymentService paymentService;
    @MockitoBean private com.dony.api.cancellation.CancellationService cancellationService;

    private static UsernamePasswordAuthenticationToken sender(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void uploadPhoto_returns201WithKey() throws Exception {
        when(bidService.uploadBidPhoto(anyString(), any())).thenReturn("bids/s/1.jpg");
        MockMultipartFile file = new MockMultipartFile(
                "file", "c.jpg", "image/jpeg", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/bids/photos").file(file)
                        .with(authentication(sender("uid-sender"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("bids/s/1.jpg"));
    }

    @Test
    void uploadPhoto_unauthenticated_isRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "c.jpg", "image/jpeg", new byte[]{1});
        mockMvc.perform(multipart("/bids/photos").file(file))
                .andExpect(status().is4xxClientError());
    }
}
