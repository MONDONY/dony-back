package com.dony.api.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
@DisplayName("UserRoleController — intégration POST /users/me/roles/traveler/activate")
class UserRoleControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;

    private static final String UID_OK       = "uid-role-it-ok";
    private static final String UID_NO_KYC   = "uid-role-it-no-kyc";
    private static final String UID_NO_STRIPE = "uid-role-it-no-stripe";

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        userRepository.save(buildUser(UID_OK,
                KycStatus.VERIFIED, StripeAccountStatus.ONBOARDING_COMPLETE));
        userRepository.save(buildUser(UID_NO_KYC,
                KycStatus.PENDING, StripeAccountStatus.ONBOARDING_COMPLETE));
        userRepository.save(buildUser(UID_NO_STRIPE,
                KycStatus.VERIFIED, StripeAccountStatus.NOT_CREATED));
    }

    @Test
    @DisplayName("KYC + Stripe OK → 200, roles contient SENDER et TRAVELER")
    void activate_returns200_whenAllRequirementsMet() throws Exception {
        mockMvc.perform(post("/users/me/roles/traveler/activate")
                        .with(authentication(auth(UID_OK, "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("SENDER", "TRAVELER")));
    }

    @Test
    @DisplayName("Déjà TRAVELER → idempotent, 200")
    void activate_idempotent_returns200() throws Exception {
        UserEntity user = userRepository.findByFirebaseUid(UID_OK).orElseThrow();
        user.getRoles().add(Role.TRAVELER);
        userRepository.save(user);

        mockMvc.perform(post("/users/me/roles/traveler/activate")
                        .with(authentication(auth(UID_OK, "ROLE_SENDER", "ROLE_TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("TRAVELER")));
    }

    @Test
    @DisplayName("KYC non vérifié → 409 avec code traveler-upgrade-requirements-missing")
    void activate_returns409_whenKycNotVerified() throws Exception {
        mockMvc.perform(post("/users/me/roles/traveler/activate")
                        .with(authentication(auth(UID_NO_KYC, "ROLE_SENDER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("traveler-upgrade-requirements-missing"))
                .andExpect(jsonPath("$.missingRequirements", hasItem("KYC_NOT_VERIFIED")));
    }

    @Test
    @DisplayName("Stripe non complété → 409 avec STRIPE_ACCOUNT_NOT_COMPLETE")
    void activate_returns409_whenStripeNotComplete() throws Exception {
        mockMvc.perform(post("/users/me/roles/traveler/activate")
                        .with(authentication(auth(UID_NO_STRIPE, "ROLE_SENDER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.missingRequirements", hasItem("STRIPE_ACCOUNT_NOT_COMPLETE")));
    }

    @Test
    @DisplayName("Sans authentification → 401")
    void activate_returns401_withoutToken() throws Exception {
        mockMvc.perform(post("/users/me/roles/traveler/activate"))
                .andExpect(status().isUnauthorized());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private UserEntity buildUser(String firebaseUid, KycStatus kycStatus,
                                  StripeAccountStatus stripeStatus) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(firebaseUid);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(kycStatus);
        u.setStripeAccountStatus(stripeStatus);
        u.setRoles(new java.util.HashSet<>(Set.of(Role.SENDER)));
        return u;
    }

    private UsernamePasswordAuthenticationToken auth(String uid, String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new UsernamePasswordAuthenticationToken(uid, null, authorities);
    }
}
