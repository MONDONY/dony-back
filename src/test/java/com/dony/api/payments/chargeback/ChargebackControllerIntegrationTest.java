package com.dony.api.payments.chargeback;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ChargebackControllerIntegrationTest {

    @Autowired MockMvc mockMvc;

    private static UsernamePasswordAuthenticationToken adminAuth() {
        return new UsernamePasswordAuthenticationToken(
                "admin-uid", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static UsernamePasswordAuthenticationToken senderAuth() {
        return new UsernamePasswordAuthenticationToken(
                "sender-uid", null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void list_returns200_forAdmin() throws Exception {
        mockMvc.perform(get("/admin/chargebacks")
                .with(authentication(adminAuth())))
                .andExpect(status().isOk());
    }

    @Test
    void list_returns403_forNonAdmin() throws Exception {
        mockMvc.perform(get("/admin/chargebacks")
                .with(authentication(senderAuth())))
                .andExpect(status().isForbidden());
    }
}
