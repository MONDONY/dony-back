package com.yadony.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le préfixe {@code /auth/**} est en permitAll : l'inscription et l'envoi d'un code
 * doivent rester joignables sans token. Mais {@code /auth/email-otp/attach} MUTE le
 * compte (il y rattache une adresse) et doit donc exiger l'authentification.
 *
 * <p>{@code @SpringBootTest} + {@code @AutoConfigureMockMvc} font tourner la vraie
 * chaîne de filtres, donc {@code SecurityConfig} lui-même — c'est le seul montage qui
 * valide réellement l'ordre des règles.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthEndpointsSecurityIT {

    @Autowired
    MockMvc mockMvc;

    /**
     * Le rejet doit venir de la chaîne de sécurité, pas de la garde du controller.
     *
     * <p>Les deux répondent 401, mais avec un {@code detail} différent : l'entry point
     * de {@code SecurityConfig} écrit « Authentication required », tandis que
     * {@code EmailOtpController.requireFirebaseUid()} lève un YadonyBusinessException dont
     * le detail est « Un token Firebase valide est requis ». Asserter le premier prouve
     * que la requête n'a jamais atteint le controller — ce qui est tout l'objet du
     * correctif : l'invariant est porté par le framework, pas par du code applicatif.
     */
    @Test
    @DisplayName("/auth/email-otp/attach sans token → rejeté par SecurityConfig, avant le controller")
    void attachWithoutToken_rejectedBySecurityChain() throws Exception {
        mockMvc.perform(post("/auth/email-otp/attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"awa@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Authentication required"));
    }

    /**
     * Garde-fou de l'autre côté : la règle ajoutée ne doit pas fermer le reste de
     * /auth/**, sinon plus personne ne pourrait demander un code pour s'inscrire.
     */
    @Test
    @DisplayName("/auth/email-otp/send reste joignable sans token")
    void sendRemainsPublic() throws Exception {
        mockMvc.perform(post("/auth/email-otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"awa@example.com\"}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401 || status == 403) {
                        throw new AssertionError(
                                "/auth/email-otp/send doit rester public, reçu " + status);
                    }
                });
    }
}
