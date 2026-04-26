package com.dony.api.e2e.steps;

import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Etantdonné;
import io.cucumber.java.fr.Quand;
import io.restassured.response.Response;
import org.assertj.core.api.Assertions;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AnnouncementSteps extends AbstractSteps {

    // ── Given ─────────────────────────────────────────────────────────────────

    @Etantdonné("il existe une annonce de {string} à {string} avec {int} kg disponibles à {decimal} €\\/kg sauvegardée sous {string}")
    public void givenAnnouncementExists(String from, String to, int kg, Double price, String alias) {
        String date = LocalDate.now().plusDays(30).toString();
        Response resp = asCurrentUser().body(buildAnnouncementBody(from, to, date, kg, price))
                .post("/announcements");
        resp.then().statusCode(201);
        ctx.saveId(alias, UUID.fromString(resp.jsonPath().getString("id")));
    }

    // ── When ──────────────────────────────────────────────────────────────────

    @Quand("je crée une annonce de {string} à {string} avec {int} kg disponibles à {decimal} €\\/kg")
    public void whenCreateAnnouncement(String from, String to, int kg, Double price) {
        String date = LocalDate.now().plusDays(30).toString();
        store(asCurrentUser().body(buildAnnouncementBody(from, to, date, kg, price)).post("/announcements"));
    }

    @Quand("je crée une annonce de {string} à {string} avec une date dans le passé")
    public void whenCreateAnnouncementPastDate(String from, String to) {
        String date = LocalDate.now().minusDays(1).toString();
        store(asCurrentUser().body(buildAnnouncementBody(from, to, date, 10, 5.0)).post("/announcements"));
    }

    @Quand("je recherche toutes les annonces")
    public void whenSearchAll() {
        store(asCurrentUser().get("/announcements"));
    }

    @Quand("je recherche les annonces au départ de {string}")
    public void whenSearchByDeparture(String city) {
        store(asCurrentUser().queryParam("departureCity", city).get("/announcements"));
    }

    @Quand("je recherche les annonces à destination de {string}")
    public void whenSearchByArrival(String city) {
        store(asCurrentUser().queryParam("arrivalCity", city).get("/announcements"));
    }

    @Quand("je consulte mes annonces")
    public void whenGetMyAnnouncements() {
        store(asCurrentUser().get("/announcements/my"));
    }

    @Quand("je consulte le détail de l'annonce {string}")
    public void whenGetAnnouncementDetail(String alias) {
        store(asCurrentUser().get("/announcements/{id}", ctx.getId(alias)));
    }

    @Quand("je modifie l'annonce {string} pour mettre {int} kg disponibles")
    public void whenUpdateAnnouncement(String alias, int kg) {
        Map<String, Object> body = buildAnnouncementBody("Paris", "Dakar",
                LocalDate.now().plusDays(30).toString(), kg, 5.0);
        store(asCurrentUser().body(body).put("/announcements/{id}", ctx.getId(alias)));
    }

    @Quand("je supprime l'annonce {string}")
    public void whenDeleteAnnouncement(String alias) {
        store(asCurrentUser().delete("/announcements/{id}", ctx.getId(alias)));
    }

    // ── Then ──────────────────────────────────────────────────────────────────

    @Alors("la réponse contient {int} annonce(s)")
    public void thenResponseContainsNAnnouncements(int count) {
        List<?> content = lastResponse().jsonPath().getList("content");
        Assertions.assertThat(content).hasSize(count);
    }

    @Alors("la réponse contient au moins {int} annonce(s)")
    public void thenResponseContainsAtLeastNAnnouncements(int count) {
        List<?> content = lastResponse().jsonPath().getList("content");
        Assertions.assertThat(content).hasSizeGreaterThanOrEqualTo(count);
    }

    @Alors("toutes les annonces partent de {string}")
    public void thenAllFromCity(String city) {
        List<String> cities = lastResponse().jsonPath().getList("content.departureCity");
        Assertions.assertThat(cities).allMatch(c -> c.equals(city));
    }

    @Alors("la réponse contient le statut d'annonce {string}")
    public void thenAnnouncementStatus(String status) {
        String actual = lastResponse().jsonPath().getString("status");
        Assertions.assertThat(actual).isEqualTo(status);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> buildAnnouncementBody(String from, String to,
                                                       String date, int kg, double price) {
        Map<String, Object> body = new HashMap<>();
        body.put("departureCity", from);
        body.put("arrivalCity", to);
        body.put("departureDate", date);
        body.put("departureTime", "08:00");
        body.put("arrivalTime", "20:00");
        body.put("departureLocation", from + " CDG");
        body.put("arrivalLocation", to + " Airport");
        body.put("availableKg", kg);
        body.put("pricePerKg", price);
        return body;
    }
}
