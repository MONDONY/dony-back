package com.dony.api.referral;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.auth.UserStatus;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("ReferralController — integration tests")
class ReferralControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ReferralCodeRepository referralCodeRepository;
    @Autowired ReferralInvitationRepository referralInvitationRepository;

    private static final String FIREBASE_UID_SENDER   = "uid-ref-it-sender-001";
    private static final String FIREBASE_UID_REFERRER = "uid-ref-it-referrer-001";

    @BeforeEach
    void setUp() {
        referralInvitationRepository.deleteAll();
        referralCodeRepository.deleteAll();
        userRepository.deleteAll();

        // Seed sender user
        UserEntity sender = new UserEntity();
        sender.setFirebaseUid(FIREBASE_UID_SENDER);
        sender.setPhoneNumber("+33600000091");
        sender.setFirstName("Ali");
        sender.setLastName("Diallo");
        sender.setStatus(UserStatus.ACTIVE);
        sender.setKycStatus(KycStatus.NOT_STARTED);
        sender.setRoles(Set.of(Role.SENDER));
        userRepository.save(sender);

        // Seed referrer user with a pre-generated code
        UserEntity referrer = new UserEntity();
        referrer.setFirebaseUid(FIREBASE_UID_REFERRER);
        referrer.setPhoneNumber("+33600000092");
        referrer.setFirstName("Jean");
        referrer.setLastName("Dupont");
        referrer.setStatus(UserStatus.ACTIVE);
        referrer.setKycStatus(KycStatus.NOT_STARTED);
        referrer.setRoles(Set.of(Role.SENDER));
        // Save and flush to get the generated UUID
        UserEntity savedReferrer = userRepository.saveAndFlush(referrer);

        ReferralCodeEntity code = new ReferralCodeEntity();
        code.setUserId(savedReferrer.getId());
        code.setCode("JEAN1234");
        referralCodeRepository.save(code);
    }

    private UsernamePasswordAuthenticationToken asSender(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    // ── 1. getMyReferral_anonymous_returns401 ─────────────────────────────────

    @Test
    @DisplayName("GET /me/referral — anonymous → 401")
    void getMyReferral_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/me/referral"))
                .andExpect(status().isUnauthorized());
    }

    // ── 2. getMyReferral_asSender_returns200 ─────────────────────────────────

    @Test
    @DisplayName("GET /me/referral — authenticated sender → 200 with code")
    void getMyReferral_asSender_returns200() throws Exception {
        mockMvc.perform(get("/me/referral")
                .with(authentication(asSender(FIREBASE_UID_SENDER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.shareUrl").exists())
                .andExpect(jsonPath("$.totalInvited").value(0))
                .andExpect(jsonPath("$.signedUp").value(0))
                .andExpect(jsonPath("$.rewarded").value(0));
    }

    // ── 3. redeemCode_missingCode_returns422 ─────────────────────────────────
    // GlobalExceptionHandler returns 422 (UNPROCESSABLE_ENTITY) for @NotBlank violations.

    @Test
    @DisplayName("POST /referral/redeem — blank code → 422 Unprocessable Entity")
    void redeemCode_missingCode_returns422() throws Exception {
        String body = objectMapper.writeValueAsString(new RedeemCodeRequest(""));
        mockMvc.perform(post("/referral/redeem")
                .with(authentication(asSender(FIREBASE_UID_SENDER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── 4. redeemCode_asSender_returns204 ────────────────────────────────────

    @Test
    @DisplayName("POST /referral/redeem — valid code → 204 No Content")
    void redeemCode_asSender_returns204() throws Exception {
        String body = objectMapper.writeValueAsString(new RedeemCodeRequest("JEAN1234"));
        mockMvc.perform(post("/referral/redeem")
                .with(authentication(asSender(FIREBASE_UID_SENDER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isNoContent());
    }

    // ── 5. regenerate_asSender_returns200 ────────────────────────────────────
    // The sender has no existing code yet, so regenerate will create one (no cooldown applies).

    @Test
    @DisplayName("POST /me/referral/regenerate — sender with no prior code → 200 with new code")
    void regenerate_asSender_returns200() throws Exception {
        // The SENDER user has no code yet in setUp — regenerate creates one fresh (no cooldown)
        mockMvc.perform(post("/me/referral/regenerate")
                .with(authentication(asSender(FIREBASE_UID_SENDER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.shareUrl").exists());
    }

    // ── 6. regenerate_withinCooldown_returns429 ───────────────────────────────

    @Test
    @DisplayName("POST /me/referral/regenerate — within cooldown → 429 Too Many Requests")
    void regenerate_withinCooldown_returns429() throws Exception {
        // REFERRER already has a code created in setUp — cooldown applies
        mockMvc.perform(post("/me/referral/regenerate")
                .with(authentication(asSender(FIREBASE_UID_REFERRER))))
                .andExpect(status().isTooManyRequests());
    }
}
