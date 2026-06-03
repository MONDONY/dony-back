package com.dony.api.auth;

import com.dony.api.auth.dto.AnalyticsConsentResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AnalyticsConsentControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AuthService authService;

    private static final String FIREBASE_UID = "uid-analytics-test";

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void GET_analyticsConsent_answered_retourne200() throws Exception {
        when(authService.getAnalyticsConsent(FIREBASE_UID))
                .thenReturn(new AnalyticsConsentResponse(true, "2026-06-03T04:55:08.960Z", "1.0"));

        mvc.perform(get("/auth/me/analytics-consent")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(true))
                .andExpect(jsonPath("$.consentAt").value("2026-06-03T04:55:08.960Z"))
                .andExpect(jsonPath("$.policyVersion").value("1.0"));

        verify(authService).getAnalyticsConsent(FIREBASE_UID);
    }

    @Test
    void GET_analyticsConsent_neverAnswered_retourne200_avecNulls() throws Exception {
        when(authService.getAnalyticsConsent(FIREBASE_UID))
                .thenReturn(new AnalyticsConsentResponse(null, null, null));

        mvc.perform(get("/auth/me/analytics-consent")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // Jackson omet les champs null par défaut : le corps est {} → les clés sont absentes.
                .andExpect(jsonPath("$.granted").doesNotExist())
                .andExpect(jsonPath("$.consentAt").doesNotExist())
                .andExpect(jsonPath("$.policyVersion").doesNotExist());

        verify(authService).getAnalyticsConsent(FIREBASE_UID);
    }

    @Test
    void PUT_analyticsConsent_retourne204_etAppelleService() throws Exception {
        doNothing().when(authService)
                .updateAnalyticsConsent(eq(FIREBASE_UID), eq(true), eq("1.0"), eq("manual"));

        mvc.perform(put("/auth/me/analytics-consent")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"granted": true, "policyVersion": "1.0", "source": "manual"}
                                """))
                .andExpect(status().isNoContent());

        verify(authService).updateAnalyticsConsent(FIREBASE_UID, true, "1.0", "manual");
    }

    @Test
    void PUT_analyticsConsent_optionnelsAbsents_retourne204() throws Exception {
        doNothing().when(authService)
                .updateAnalyticsConsent(eq(FIREBASE_UID), eq(false), isNull(), isNull());

        mvc.perform(put("/auth/me/analytics-consent")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"granted": false}
                                """))
                .andExpect(status().isNoContent());

        verify(authService).updateAnalyticsConsent(FIREBASE_UID, false, null, null);
    }

    @Test
    void PUT_analyticsConsent_grantedManquant_retourne400ou422() throws Exception {
        mvc.perform(put("/auth/me/analytics-consent")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status != 400 && status != 422) {
                        throw new AssertionError("Attendu 400 ou 422, obtenu " + status);
                    }
                });

        verify(authService, never()).updateAnalyticsConsent(any(), anyBoolean(), any(), any());
    }

    @Test
    void GET_analyticsConsent_sansAuth_retourne401() throws Exception {
        mvc.perform(get("/auth/me/analytics-consent")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void PUT_analyticsConsent_sansAuth_retourne401() throws Exception {
        mvc.perform(put("/auth/me/analytics-consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"granted": true}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
