package com.dony.api.matching;

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
class MarketPriceControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;

    @BeforeEach
    void clean() {
        announcementRepository.deleteAll();
    }

    @Test
    void marketPrice_returnsMedian_whenAnnouncementsExistOnCorridor() throws Exception {
        seedAnnouncement("Paris", "Dakar", new BigDecimal("8"));
        seedAnnouncement("Paris", "Dakar", new BigDecimal("10"));
        seedAnnouncement("Paris", "Dakar", new BigDecimal("12"));

        mockMvc.perform(get("/announcements/market-price")
                .param("corridor", "PARIS_DAKAR")
                .with(authentication(auth("uid-test"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.median").value(10.0))
            .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void marketPrice_returnsNull_whenNoAnnouncementsOnCorridor() throws Exception {
        mockMvc.perform(get("/announcements/market-price")
                .param("corridor", "PARIS_BAMAKO")
                .with(authentication(auth("uid-test"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.median").doesNotExist())
            .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void marketPrice_returnsNull_whenCorridorUnknown() throws Exception {
        mockMvc.perform(get("/announcements/market-price")
                .param("corridor", "UNKNOWN_CORRIDOR")
                .with(authentication(auth("uid-test"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.median").doesNotExist())
            .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void marketPrice_evenCount_returnsAverageOfTwoMiddle() throws Exception {
        // 4 values: 6, 8, 12, 14 → median = (8+12)/2 = 10
        seedAnnouncement("Lyon", "Abidjan", new BigDecimal("6"));
        seedAnnouncement("Lyon", "Abidjan", new BigDecimal("8"));
        seedAnnouncement("Lyon", "Abidjan", new BigDecimal("12"));
        seedAnnouncement("Lyon", "Abidjan", new BigDecimal("14"));

        mockMvc.perform(get("/announcements/market-price")
                .param("corridor", "LYON_ABIDJAN")
                .with(authentication(auth("uid-test"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.median").value(10.0))
            .andExpect(jsonPath("$.currency").value("EUR"));
    }

    private void seedAnnouncement(String dep, String arr, BigDecimal pricePerKg) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity(dep);
        a.setArrivalCity(arr);
        a.setDepartureDate(LocalDate.now().plusDays(5));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Pickup");
        a.setPickupLat(new BigDecimal("48.86"));
        a.setPickupLng(new BigDecimal("2.33"));
        a.setDeliveryAddressLabel("Delivery");
        a.setDeliveryLat(new BigDecimal("14.69"));
        a.setDeliveryLng(new BigDecimal("-17.44"));
        a.setAvailableKg(new BigDecimal("23"));
        a.setTotalKg(new BigDecimal("23"));
        a.setPricePerKg(pricePerKg);
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
        announcementRepository.save(a);
    }

    private static UsernamePasswordAuthenticationToken auth(String uid) {
        return new UsernamePasswordAuthenticationToken(uid, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }
}
