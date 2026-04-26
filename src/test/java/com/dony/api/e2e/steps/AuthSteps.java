package com.dony.api.e2e.steps;

import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Etantdonné;
import io.cucumber.java.fr.Quand;
import io.restassured.response.Response;
import org.assertj.core.api.Assertions;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class AuthSteps extends AbstractSteps {

    // ── Given ─────────────────────────────────────────────────────────────────

    @Etantdonné("un token Firebase pour l'uid {string}")
    public void givenFirebaseToken(String uid) {
        ctx.setCurrentUser(uid, "");
    }

    @Etantdonné("un utilisateur VOYAGEUR enregistré avec l'uid {string} et le téléphone {string}")
    public void givenRegisteredTraveler(String uid, String phone) {
        ctx.setCurrentUser(uid, "ROLE_TRAVELER");
        store(asCurrentUser().body(Map.of(
                "phoneNumber", phone,
                "roles", Set.of("TRAVELER")
        )).post("/auth/register"));
        ctx.setCurrentUser(uid, "ROLE_TRAVELER");
    }

    @Etantdonné("un utilisateur EXPÉDITEUR enregistré avec l'uid {string} et le téléphone {string}")
    public void givenRegisteredSender(String uid, String phone) {
        ctx.setCurrentUser(uid, "ROLE_SENDER");
        store(asCurrentUser().body(Map.of(
                "phoneNumber", phone,
                "roles", Set.of("SENDER")
        )).post("/auth/register"));
        ctx.setCurrentUser(uid, "ROLE_SENDER");
    }

    // ── When ──────────────────────────────────────────────────────────────────

    @Quand("je m'inscris avec le téléphone {string} et le rôle {string}")
    public void whenRegister(String phone, String role) {
        Response resp = asCurrentUser().body(Map.of(
                "phoneNumber", phone,
                "roles", Set.of(role)
        )).post("/auth/register");
        store(resp);
    }

    @Quand("je m'inscris avec le téléphone {string} et les rôles {string} et {string}")
    public void whenRegisterBothRoles(String phone, String role1, String role2) {
        Response resp = asCurrentUser().body(Map.of(
                "phoneNumber", phone,
                "roles", Set.of(role1, role2)
        )).post("/auth/register");
        store(resp);
    }

    @Quand("je consulte mon profil")
    public void whenGetProfile() {
        store(asCurrentUser().get("/auth/me"));
    }

    @Quand("je mets à jour mon profil avec le prénom {string} et le nom {string}")
    public void whenUpdateProfile(String firstName, String lastName) {
        store(asCurrentUser().body(Map.of(
                "firstName", firstName,
                "lastName", lastName
        )).patch("/auth/me"));
    }

    @Quand("je supprime mon compte")
    public void whenDeleteAccount() {
        store(asCurrentUser().delete("/auth/me"));
    }

    // ── Then ──────────────────────────────────────────────────────────────────

    @Alors("la réponse contient un identifiant utilisateur")
    public void thenResponseHasUserId() {
        String id = lastResponse().jsonPath().getString("id");
        Assertions.assertThat(id).isNotBlank();
    }

    @Alors("la réponse contient le rôle {string}")
    public void thenResponseHasRole(String role) {
        List<String> roles = lastResponse().jsonPath().getList("roles");
        Assertions.assertThat(roles).contains(role);
    }

    @Alors("le profil contient le prénom {string} et le nom {string}")
    public void thenProfileHasName(String firstName, String lastName) {
        Assertions.assertThat(lastResponse().jsonPath().getString("firstName")).isEqualTo(firstName);
        Assertions.assertThat(lastResponse().jsonPath().getString("lastName")).isEqualTo(lastName);
    }

    @Alors("le numéro de téléphone {string} est déjà pris")
    public void thenPhoneAlreadyTaken(String phone) {
        String code = lastResponse().jsonPath().getString("properties.code");
        Assertions.assertThat(code).isEqualTo("phone-already-exists");
    }
}
