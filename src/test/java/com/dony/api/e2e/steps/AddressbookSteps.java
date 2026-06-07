package com.dony.api.e2e.steps;

import io.cucumber.java.fr.Quand;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Steps for the address book: pickup addresses, delivery addresses and recipients.
 */
public class AddressbookSteps extends AbstractSteps {

    // ── Pickup addresses ──────────────────────────────────────────────────────

    @Quand("je crée une adresse de retrait {string} sauvegardée sous {string}")
    public void whenCreatePickup(String label, String alias) {
        Map<String, Object> body = new HashMap<>();
        body.put("label", label);
        body.put("street", "12 rue de la Paix");
        body.put("postalCode", "75002");
        body.put("city", "Paris");
        body.put("country", "FR");
        body.put("floorApartment", "3e étage");
        body.put("instructions", "Sonner à l'interphone");
        body.put("isDefault", true);
        Response resp = asCurrentUser().body(body).post("/addressbook/pickup-addresses");
        store(resp);
        saveIfPresent(alias);
    }

    @Quand("je consulte mes adresses de retrait")
    public void whenListPickup() {
        store(asCurrentUser().get("/addressbook/pickup-addresses"));
    }

    @Quand("je modifie l'adresse de retrait {string} avec le libellé {string}")
    public void whenUpdatePickup(String alias, String label) {
        Map<String, Object> body = new HashMap<>();
        body.put("label", label);
        body.put("street", "8 avenue des Champs");
        body.put("postalCode", "75008");
        body.put("city", "Paris");
        body.put("country", "FR");
        body.put("isDefault", false);
        store(asCurrentUser().body(body).put("/addressbook/pickup-addresses/{id}", ctx.getId(alias)));
    }

    @Quand("je définis l'adresse de retrait {string} comme adresse par défaut")
    public void whenSetDefaultPickup(String alias) {
        store(asCurrentUser().patch("/addressbook/pickup-addresses/{id}/set-default", ctx.getId(alias)));
    }

    @Quand("je supprime l'adresse de retrait {string}")
    public void whenDeletePickup(String alias) {
        store(asCurrentUser().delete("/addressbook/pickup-addresses/{id}", ctx.getId(alias)));
    }

    // ── Delivery addresses ────────────────────────────────────────────────────

    @Quand("je crée une adresse de livraison {string} sauvegardée sous {string}")
    public void whenCreateDelivery(String label, String alias) {
        Map<String, Object> body = new HashMap<>();
        body.put("label", label);
        body.put("street", "Quartier Plateau");
        body.put("city", "Dakar");
        body.put("country", "SN");
        body.put("isDefault", true);
        Response resp = asCurrentUser().body(body).post("/addressbook/delivery-addresses");
        store(resp);
        saveIfPresent(alias);
    }

    @Quand("je consulte mes adresses de livraison")
    public void whenListDelivery() {
        store(asCurrentUser().get("/addressbook/delivery-addresses"));
    }

    @Quand("je supprime l'adresse de livraison {string}")
    public void whenDeleteDelivery(String alias) {
        store(asCurrentUser().delete("/addressbook/delivery-addresses/{id}", ctx.getId(alias)));
    }

    // ── Recipients ────────────────────────────────────────────────────────────

    @Quand("je crée un destinataire {string} sauvegardé sous {string}")
    public void whenCreateRecipient(String fullName, String alias) {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", fullName);
        body.put("relationship", "Famille");
        body.put("phoneE164", "+221771234567");
        body.put("city", "Dakar");
        body.put("country", "SN");
        body.put("notes", "Disponible le matin");
        Response resp = asCurrentUser().body(body).post("/addressbook/recipients");
        store(resp);
        saveIfPresent(alias);
    }

    @Quand("je consulte mes destinataires")
    public void whenListRecipients() {
        store(asCurrentUser().get("/addressbook/recipients"));
    }

    @Quand("je modifie le destinataire {string} avec le nom {string}")
    public void whenUpdateRecipient(String alias, String fullName) {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", fullName);
        body.put("phoneE164", "+221770000000");
        body.put("city", "Abidjan");
        body.put("country", "CI");
        store(asCurrentUser().body(body).put("/addressbook/recipients/{id}", ctx.getId(alias)));
    }

    @Quand("je supprime le destinataire {string}")
    public void whenDeleteRecipient(String alias) {
        store(asCurrentUser().delete("/addressbook/recipients/{id}", ctx.getId(alias)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void saveIfPresent(String alias) {
        String id = lastResponse().jsonPath().getString("id");
        if (id != null) {
            ctx.saveId(alias, UUID.fromString(id));
        }
    }
}
