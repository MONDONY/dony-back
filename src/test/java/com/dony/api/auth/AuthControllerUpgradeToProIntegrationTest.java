package com.dony.api.auth;

import com.dony.api.auth.dto.UpgradeToProRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code POST /auth/me/upgrade-to-pro}.
 *
 * <p>Uses {@code @ActiveProfiles("test")} to leverage the H2 in-memory DB.
 * Each test seeds a user directly via {@link UserRepository}.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("POST /auth/me/upgrade-to-pro — integration tests")
class AuthControllerUpgradeToProIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;

    private static final String FIREBASE_UID = "uid-pro-it-001";
    private static final String FIREBASE_UID_WITH_STRIPE = "uid-pro-it-002";

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        // Seed a plain user (no Stripe account)
        UserEntity plainUser = new UserEntity();
        plainUser.setFirebaseUid(FIREBASE_UID);
        plainUser.setPhoneNumber("+33612000001");
        plainUser.setStatus(UserStatus.ACTIVE);
        plainUser.setKycStatus(KycStatus.PENDING);
        plainUser.setRoles(Set.of(Role.SENDER, Role.TRAVELER));
        plainUser.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        userRepository.save(plainUser);

        // Seed a user who already has a Stripe account
        UserEntity stripeUser = new UserEntity();
        stripeUser.setFirebaseUid(FIREBASE_UID_WITH_STRIPE);
        stripeUser.setPhoneNumber("+33612000002");
        stripeUser.setStatus(UserStatus.ACTIVE);
        stripeUser.setKycStatus(KycStatus.PENDING);
        stripeUser.setRoles(Set.of(Role.TRAVELER));
        stripeUser.setStripeAccountId("acct_existing_123");
        stripeUser.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        userRepository.save(stripeUser);
    }

    private UsernamePasswordAuthenticationToken authenticatedAs(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"),
                        new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @Test
    @DisplayName("200 OK with valid body → isProAccount=true, stripeAccountStatus=NOT_CREATED, country=FR in response")
    void upgradeToPro_success_returns200() throws Exception {
        UpgradeToProRequest request = new UpgradeToProRequest("Dony SARL", "12345678901234");

        mockMvc.perform(post("/auth/me/upgrade-to-pro")
                        .with(authentication(authenticatedAs(FIREBASE_UID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.phoneNumber").value("+33612000001"))
                .andExpect(jsonPath("$.isProAccount").value(true))
                .andExpect(jsonPath("$.stripeAccountStatus").value("NOT_CREATED"))
                .andExpect(jsonPath("$.country").value("FR"));
    }

    @Test
    @DisplayName("409 Conflict when user already has a Stripe Connect account")
    void upgradeToPro_stripeAccountExists_returns409() throws Exception {
        UpgradeToProRequest request = new UpgradeToProRequest("Dony SARL", "12345678901234");

        mockMvc.perform(post("/auth/me/upgrade-to-pro")
                        .with(authentication(authenticatedAs(FIREBASE_UID_WITH_STRIPE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stripe-account-exists"));
    }

    @Test
    @DisplayName("401 Unauthorized when no authentication provided")
    void upgradeToPro_noAuth_returns401() throws Exception {
        UpgradeToProRequest request = new UpgradeToProRequest("Dony SARL", "12345678901234");

        mockMvc.perform(post("/auth/me/upgrade-to-pro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("422 Unprocessable when siret is invalid (not 14 digits)")
    void upgradeToPro_invalidSiret_returns422() throws Exception {
        UpgradeToProRequest request = new UpgradeToProRequest("Dony SARL", "1234567"); // too short

        mockMvc.perform(post("/auth/me/upgrade-to-pro")
                        .with(authentication(authenticatedAs(FIREBASE_UID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid-siret"));
    }
}
