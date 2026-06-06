package com.dony.api.e2e.steps;

import io.cucumber.java.fr.Quand;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Steps for the traveler price grid (catalogue of fixed prices per parcel type).
 */
public class CatalogSteps extends AbstractSteps {

    @Quand("je crée un article de grille tarifaire {string} à {decimal} € sauvegardé sous {string}")
    public void whenCreatePriceGridItem(String label, Double unitPriceNet, String alias) {
        Map<String, Object> body = new HashMap<>();
        body.put("label", label);
        body.put("unitPriceNet", unitPriceNet);
        Response resp = asCurrentUser().body(body).post("/travelers/me/price-grid/items");
        store(resp);
        String id = resp.jsonPath().getString("id");
        if (id != null) {
            ctx.saveId(alias, UUID.fromString(id));
        }
    }

    @Quand("je consulte ma grille tarifaire")
    public void whenGetPriceGrid() {
        store(asCurrentUser().get("/travelers/me/price-grid"));
    }

    @Quand("je modifie l'article de grille tarifaire {string} avec le libellé {string}")
    public void whenUpdatePriceGridItem(String alias, String label) {
        Map<String, Object> body = new HashMap<>();
        body.put("label", label);
        body.put("unitPriceNet", 15.0);
        store(asCurrentUser().body(body).put("/travelers/me/price-grid/items/{id}", ctx.getId(alias)));
    }

    @Quand("je supprime l'article de grille tarifaire {string}")
    public void whenDeletePriceGridItem(String alias) {
        store(asCurrentUser().delete("/travelers/me/price-grid/items/{id}", ctx.getId(alias)));
    }

    // ── Trip templates ────────────────────────────────────────────────────────

    @Quand("je crée un modèle de trajet {string} sauvegardé sous {string}")
    public void whenCreateTripTemplate(String label, String alias) {
        Map<String, Object> body = new HashMap<>();
        body.put("label", label);
        body.put("emoji", "✈️");
        body.put("departureCity", "Paris");
        body.put("arrivalCity", "Dakar");
        body.put("transportMode", "PLANE");
        body.put("capacityUnit", "KG");
        body.put("availableKg", 20);
        body.put("pricePerKg", 5.0);
        body.put("cashAccepted", true);
        body.put("arrivalTime", "20:00");
        Response resp = asCurrentUser().body(body).post("/trip-templates");
        store(resp);
        saveId(alias);
    }

    @Quand("je consulte mes modèles de trajet")
    public void whenListTripTemplates() {
        store(asCurrentUser().get("/trip-templates"));
    }

    @Quand("je supprime le modèle de trajet {string}")
    public void whenDeleteTripTemplate(String alias) {
        store(asCurrentUser().delete("/trip-templates/{id}", ctx.getId(alias)));
    }

    // ── Trip recurrences ──────────────────────────────────────────────────────

    @Quand("je crée une récurrence de trajet sauvegardée sous {string}")
    public void whenCreateTripRecurrence(String alias) {
        Map<String, Object> body = new HashMap<>();
        body.put("departureCity", "Paris");
        body.put("arrivalCity", "Dakar");
        body.put("transportMode", "PLANE");
        body.put("capacityUnit", "KG");
        body.put("availableKg", 20.0);
        body.put("pricePerKg", 5.0);
        body.put("pickupAddress", Map.of("label", "Paris CDG", "lat", 48.8566, "lng", 2.3522));
        body.put("deliveryAddress", Map.of("label", "Dakar DSS", "lat", 14.7397, "lng", -17.4902));
        body.put("departureTime", "08:00");
        body.put("arrivalTime", "20:00");
        body.put("cashAccepted", true);
        body.put("weekdays", "1111100");
        body.put("horizonDays", 30);
        body.put("active", true);
        Response resp = asCurrentUser().body(body).post("/trip-recurrences");
        store(resp);
        saveId(alias);
    }

    @Quand("je consulte mes récurrences de trajet")
    public void whenListTripRecurrences() {
        store(asCurrentUser().get("/trip-recurrences"));
    }

    @Quand("je supprime la récurrence de trajet {string}")
    public void whenDeleteTripRecurrence(String alias) {
        store(asCurrentUser().delete("/trip-recurrences/{id}", ctx.getId(alias)));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void saveId(String alias) {
        String id = lastResponse().jsonPath().getString("id");
        if (id != null) {
            ctx.saveId(alias, UUID.fromString(id));
        }
    }
}
