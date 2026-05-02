package com.dony.api.matching;

import com.dony.api.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AnnouncementControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;

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

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private AnnouncementEntity seedAnnouncement(double lat, double lng, String dep, String arr) {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(testTravelerId);
        e.setDepartureCity(dep);
        e.setArrivalCity(arr);
        e.setDepartureDate(LocalDate.now().plusDays(7));
        e.setAvailableKg(new BigDecimal("8"));
        e.setPricePerKg(new BigDecimal("12"));
        e.setStatus(AnnouncementStatus.ACTIVE);
        e.setPickupAddressLabel("Test pickup");
        e.setPickupLat(BigDecimal.valueOf(lat));
        e.setPickupLng(BigDecimal.valueOf(lng));
        e.setDeliveryAddressLabel("Test delivery");
        e.setDeliveryLat(BigDecimal.valueOf(14.6928));
        e.setDeliveryLng(BigDecimal.valueOf(-17.4467));
        return announcementRepository.save(e);
    }
}
