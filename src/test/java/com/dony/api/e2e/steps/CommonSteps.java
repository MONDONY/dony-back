package com.dony.api.e2e.steps;

import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Etantdonné;
import io.cucumber.java.fr.Quand;
import org.assertj.core.api.Assertions;

import java.util.UUID;

/**
 * Reusable steps shared across all features.
 */
public class CommonSteps extends AbstractSteps {

    // ── Authentication context ────────────────────────────────────────────────

    @Etantdonné("l'utilisateur {string} est authentifié en tant que VOYAGEUR")
    public void givenAuthenticatedTraveler(String uid) {
        ctx.setCurrentUser(uid, "ROLE_TRAVELER");
    }

    @Etantdonné("l'utilisateur {string} est authentifié en tant qu'EXPÉDITEUR")
    public void givenAuthenticatedSender(String uid) {
        ctx.setCurrentUser(uid, "ROLE_SENDER");
    }

    @Etantdonné("l'utilisateur {string} est authentifié avec les rôles VOYAGEUR et EXPÉDITEUR")
    public void givenAuthenticatedBothRoles(String uid) {
        ctx.setCurrentUser(uid, "ROLE_TRAVELER,ROLE_SENDER");
    }

    @Etantdonné("aucun utilisateur n'est authentifié")
    public void givenNoAuth() {
        ctx.setCurrentUser(null, null);
    }

    // ── HTTP assertions ───────────────────────────────────────────────────────

    @Alors("la réponse HTTP est {int}")
    public void thenHttpStatus(int expectedStatus) {
        lastResponse().then().statusCode(expectedStatus);
    }

    @Alors("le code d'erreur de la réponse est {string}")
    public void thenErrorCode(String expectedCode) {
        String actual = lastResponse().jsonPath().getString("properties.code");
        // Try alternate path used by some error responses
        if (actual == null) {
            actual = lastResponse().jsonPath().getString("code");
        }
        Assertions.assertThat(actual)
                .as("Error code in response body")
                .isEqualTo(expectedCode);
    }

    @Alors("l'identifiant de la réponse est sauvegardé sous {string}")
    public void thenSaveId(String alias) {
        String idStr = lastResponse().jsonPath().getString("id");
        ctx.saveId(alias, UUID.fromString(idStr));
    }
}
