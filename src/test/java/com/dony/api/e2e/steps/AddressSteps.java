package com.dony.api.e2e.steps;

import io.cucumber.java.fr.Quand;

import java.util.HashMap;
import java.util.Map;

/**
 * Steps for the Google Places address subsystem (autocomplete, details, reverse geocode).
 * The underlying RestTemplate is stubbed in the e2e profile (see E2EMockConfig).
 */
public class AddressSteps extends AbstractSteps {

    @Quand("je recherche des adresses pour {string}")
    public void whenAutocomplete(String query) {
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("sessionToken", "session-token-001");
        store(asCurrentUser().body(body).post("/addresses/autocomplete"));
    }

    @Quand("je consulte les détails du lieu {string}")
    public void whenPlaceDetails(String placeId) {
        Map<String, Object> body = new HashMap<>();
        body.put("placeId", placeId);
        body.put("sessionToken", "session-token-001");
        store(asCurrentUser().body(body).post("/addresses/details"));
    }

    @Quand("je géocode des coordonnées valides")
    public void whenReverseGeocode() {
        Map<String, Object> body = new HashMap<>();
        body.put("lat", 14.6928);
        body.put("lng", -17.4467);
        store(asCurrentUser().body(body).post("/addresses/reverse"));
    }
}
