package com.dony.api.matching;

import com.dony.api.auth.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AnnouncementControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;
    @Autowired ObjectMapper objectMapper;

    /**
     * Reuse the same traveler UUID across all tests (no UserEntity needed;
     * toSearchResponse handles Optional.empty() for the traveler profile).
     */
    private static final UUID testTravelerId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Auth helper: matches what FirebaseTokenFilter puts in the SecurityContext
     * (a String principal = Firebase UID).
     */
    private static UsernamePasswordAuthenticationToken authenticatedAs(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @BeforeEach
    void cleanDb() {
        announcementRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ─── Radius filter tests ──────────────────────────────────────────────────

    @Test
    void searchAnnouncements_withRadius_returnsOnlyNearby() throws Exception {
        // GIVEN: 3 Paris→Dakar announcements at known pickup coords
        seedAnnouncement(48.8566, 2.3522, "Paris", "Dakar");   // Paris center
        seedAnnouncement(48.8600, 2.3500, "Paris", "Dakar");   // ~0.5 km from Paris1
        seedAnnouncement(45.7640, 4.8357, "Paris", "Dakar");   // Lyon coords (~400 km from Paris)

        // WHEN: search with userLat/Lng=Paris center, radius=10km
        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .param("userLat", "48.8566")
                .param("userLng", "2.3522")
                .param("radiusKm", "10"))
            .andExpect(status().isOk())
            // THEN: only the 2 Paris-area announcements are returned
            .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void searchAnnouncements_withRadius_excludesFarAnnouncements() throws Exception {
        // GIVEN: 1 announcement far from the user + 1 announcement nearby
        // Far: Lyon (~400 km from Paris)
        seedAnnouncement(45.7640, 4.8357, "Paris", "Dakar");
        // Nearby: Paris center
        seedAnnouncement(48.8566, 2.3522, "Paris", "Dakar");

        // WHEN: search with radius=100km around Paris
        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .param("userLat", "48.8566")
                .param("userLng", "2.3522")
                .param("radiusKm", "100"))
            .andExpect(status().isOk())
            // THEN: only the 1 Paris announcement (Lyon is ~400 km away, outside 100 km)
            .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void searchAnnouncements_withRadius_combinesWithCityFilter() throws Exception {
        // GIVEN: a Paris→Dakar near user + a Lyon→Dakar near user
        // (coords forced near Paris to isolate the city-filter logic)
        seedAnnouncement(48.8566, 2.3522, "Paris", "Dakar");
        seedAnnouncement(48.8600, 2.3500, "Lyon",  "Dakar"); // pickup near Paris but city=Lyon

        // WHEN: search with city=Paris + radius=5km
        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .param("departureCity", "Paris")
                .param("userLat", "48.8566")
                .param("userLng", "2.3522")
                .param("radiusKm", "5"))
            .andExpect(status().isOk())
            // THEN: only the Paris one (AND semantics — city filter AND radius filter)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].departureCity").value("Paris"));
    }

    // ─── Create — transportMode validation ────────────────────────────────────

    @Test
    void createAnnouncement_withMissingTransportMode_returns422() throws Exception {
        // GIVEN: a valid create payload that OMITS transportMode
        var body = """
            {
              "departureCity": "Paris",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "availableKg": 10,
              "pricePerKg": 5,
              "pickupAddress": {"label": "Lyon", "lat": 45.748, "lng": 4.846},
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447}
            }
            """.formatted(LocalDate.now().plusDays(10));

        // WHEN: POST /announcements without transportMode
        // THEN: validation rejects the payload (RFC 7807, 422 per GlobalExceptionHandler)
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    // ─── Create — transportMode happy paths ───────────────────────────────────

    @Test
    void createAnnouncement_withEachTransportMode_returns201() throws Exception {
        seedTraveler("uid-test-traveler");
        for (var mode : List.of("PLANE", "CAR", "TRAIN", "BUS", "BOAT", "OTHER")) {
            announcementRepository.deleteAll();
            mockMvc.perform(post("/announcements")
                    .with(authentication(authenticatedAs("uid-test-traveler")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validBodyWithMode(mode)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transportMode").value(mode));
        }
    }

    @Test
    void createAnnouncement_exposesPricePerKgDisplay_netPlus12Percent() throws Exception {
        // Cohérence des prix : le DTO expose pricePerKgDisplay = pricePerKg (net) × 1.12,
        // symétrique de unitPriceDisplay du mode MIXED, pour l'affichage côté expéditeur.
        seedTraveler("uid-test-traveler");
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBodyWithMode("PLANE")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.pricePerKgDisplay").value(5.60));
    }

    @Test
    void createAnnouncement_withCountryCodes_exposesCodesAndFlags() throws Exception {
        // Les codes pays ISO-2 envoyés à la création sont persistés et renvoyés,
        // accompagnés de leurs drapeaux emoji résolus par FlagService (US → 🇺🇸).
        seedTraveler("uid-test-traveler");
        String body = """
            {
              "departureCity": "New York",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "availableKg": 10,
              "pricePerKg": 5,
              "transportMode": "PLANE",
              "pickupAddress": {"label": "JFK", "lat": 40.641, "lng": -73.778},
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447},
              "departureCountryCode": "US",
              "arrivalCountryCode": "SN"
            }
            """.formatted(LocalDate.now().plusDays(10));
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.departureCountryCode").value("US"))
            .andExpect(jsonPath("$.arrivalCountryCode").value("SN"))
            .andExpect(jsonPath("$.departureFlag").value("🇺🇸"))
            .andExpect(jsonPath("$.arrivalFlag").value("🇸🇳"));
    }

    @Test
    void createAnnouncement_withKgExactCapacityUnit_returns201() throws Exception {
        // Régression : capacityUnit "KG_EXACT" (capacité personnalisée saisie par le
        // voyageur) doit être désérialisé sans 400 « Malformed request payload ».
        seedTraveler("uid-test-traveler");
        String body = """
            {
              "departureCity": "Paris",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "availableKg": 15,
              "pricePerKg": 5,
              "transportMode": "PLANE",
              "capacityUnit": "KG_EXACT",
              "pickupAddress": {"label": "Lyon", "lat": 45.748, "lng": 4.846},
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447}
            }
            """.formatted(LocalDate.now().plusDays(10));
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.capacityUnit").value("KG_EXACT"))
            .andExpect(jsonPath("$.availableKg").value(15));
    }

    @Test
    void createAnnouncement_withInvalidTransportMode_returns400() throws Exception {
        seedTraveler("uid-test-traveler");
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBodyWithMode("BIKE")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateAnnouncement_changesTransportMode_returns200() throws Exception {
        seedTraveler("uid-test-traveler");
        var createRes = mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBodyWithMode("PLANE")))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode created = objectMapper.readTree(createRes.getResponse().getContentAsString());
        String id = created.get("id").asText();

        mockMvc.perform(put("/announcements/" + id)
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBodyWithMode("TRAIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transportMode").value("TRAIN"));
    }

    @Test
    void searchAnnouncements_returnsTransportModeInResults() throws Exception {
        seedTraveler("uid-test-traveler");
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBodyWithMode("BOAT")))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].transportMode").value("BOAT"));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String validBodyWithMode(String mode) {
        return """
            {
              "departureCity": "Paris",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "availableKg": 10,
              "pricePerKg": 5,
              "transportMode": "%s",
              "pickupAddress": {"label": "Lyon", "lat": 45.748, "lng": 4.846},
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447}
            }
            """.formatted(LocalDate.now().plusDays(10), mode);
    }

    private com.dony.api.auth.UserEntity seedTraveler(String firebaseUid) {
        var user = new com.dony.api.auth.UserEntity();
        user.setFirebaseUid(firebaseUid);
        user.setPhoneNumber("+33600000000");
        user.setStatus(com.dony.api.auth.UserStatus.ACTIVE);
        user.setKycStatus(com.dony.api.auth.KycStatus.PENDING);
        user.setRoles(new java.util.HashSet<>(List.of(com.dony.api.auth.Role.TRAVELER)));
        return userRepository.save(user);
    }

    private AnnouncementEntity seedAnnouncement(double lat, double lng, String dep, String arr) {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(testTravelerId);
        e.setDepartureCity(dep);
        e.setArrivalCity(arr);
        e.setDepartureDate(LocalDate.now().plusDays(7));
        e.setAvailableKg(new BigDecimal("8"));
        e.setTotalKg(new BigDecimal("8"));
        e.setPricePerKg(new BigDecimal("12"));
        e.setStatus(AnnouncementStatus.ACTIVE);
        e.setTransportMode(TransportMode.PLANE);
        e.setPickupAddressLabel("Test pickup");
        e.setPickupLat(BigDecimal.valueOf(lat));
        e.setPickupLng(BigDecimal.valueOf(lng));
        e.setDeliveryAddressLabel("Test delivery");
        e.setDeliveryLat(BigDecimal.valueOf(14.6928));
        e.setDeliveryLng(BigDecimal.valueOf(-17.4467));
        return announcementRepository.save(e);
    }
}
