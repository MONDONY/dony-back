package com.dony.api.favorites;

import com.dony.api.favorites.dto.FavoriteIdsResponse;
import com.dony.api.matching.dto.AnnouncementSearchResponse;
import com.dony.api.requests.dto.PackageRequestSearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FavoriteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FavoriteService favoriteService;

    private static final String SENDER_UID = "uid-sender-test";
    private static final String TRAVELER_UID = "uid-traveler-test";
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // ── Auth helpers (same pattern as PriceGridControllerTest) ────────────────

    private static UsernamePasswordAuthenticationToken asSender() {
        return new UsernamePasswordAuthenticationToken(
                SENDER_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private static UsernamePasswordAuthenticationToken asTraveler() {
        return new UsernamePasswordAuthenticationToken(
                TRAVELER_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    // ── PUT /favorites/trip/{id} ──────────────────────────────────────────────

    @Test
    void putTrip_asSender_returns200_andCallsService() throws Exception {
        doNothing().when(favoriteService)
                .addFavorite(eq(SENDER_UID), eq(FavoriteTargetType.TRIP), eq(TARGET_ID));

        mockMvc.perform(put("/favorites/trip/" + TARGET_ID)
                        .with(authentication(asSender())))
                .andExpect(status().isOk());

        verify(favoriteService).addFavorite(SENDER_UID, FavoriteTargetType.TRIP, TARGET_ID);
    }

    @Test
    void putPackageRequest_asSender_returns403() throws Exception {
        mockMvc.perform(put("/favorites/package-request/" + TARGET_ID)
                        .with(authentication(asSender())))
                .andExpect(status().isForbidden());
    }

    @Test
    void putPackageRequest_asTraveler_returns200() throws Exception {
        doNothing().when(favoriteService)
                .addFavorite(eq(TRAVELER_UID), eq(FavoriteTargetType.PACKAGE_REQUEST), eq(TARGET_ID));

        mockMvc.perform(put("/favorites/package-request/" + TARGET_ID)
                        .with(authentication(asTraveler())))
                .andExpect(status().isOk());

        verify(favoriteService).addFavorite(TRAVELER_UID, FavoriteTargetType.PACKAGE_REQUEST, TARGET_ID);
    }

    @Test
    void putUnknownType_returns400() throws Exception {
        mockMvc.perform(put("/favorites/bogus/" + TARGET_ID)
                        .with(authentication(asSender())))
                .andExpect(status().isBadRequest());
    }

    // ── DELETE /favorites/trip/{id} ──────────────────────────────────────────

    @Test
    void deleteTrip_asTraveler_returns204() throws Exception {
        doNothing().when(favoriteService)
                .removeFavorite(eq(TRAVELER_UID), eq(FavoriteTargetType.TRIP), eq(TARGET_ID));

        mockMvc.perform(delete("/favorites/trip/" + TARGET_ID)
                        .with(authentication(asTraveler())))
                .andExpect(status().isNoContent());
    }

    // ── DELETE /favorites/package-request/{id} ───────────────────────────────

    @Test
    void deletePackageRequest_asTraveler_returns204() throws Exception {
        doNothing().when(favoriteService)
                .removeFavorite(eq(TRAVELER_UID), eq(FavoriteTargetType.PACKAGE_REQUEST), eq(TARGET_ID));

        mockMvc.perform(delete("/favorites/package-request/" + TARGET_ID)
                        .with(authentication(asTraveler())))
                .andExpect(status().isNoContent());

        verify(favoriteService).removeFavorite(TRAVELER_UID, FavoriteTargetType.PACKAGE_REQUEST, TARGET_ID);
    }

    // ── RFC 7807 ProblemDetail assertions ────────────────────────────────────

    @Test
    void putUnknownType_returns400_withProblemDetail() throws Exception {
        mockMvc.perform(put("/favorites/bogus/" + TARGET_ID)
                        .with(authentication(asSender())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists());
    }

    @Test
    void putPackageRequest_asSender_returns403_withProblemDetail() throws Exception {
        mockMvc.perform(put("/favorites/package-request/" + TARGET_ID)
                        .with(authentication(asSender())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists());
    }

    // ── GET /favorites/trips ──────────────────────────────────────────────────

    @Test
    void getTrips_asSender_returns200WithList() throws Exception {
        // Use an empty list — the controller just passes through whatever service returns
        when(favoriteService.getFavoriteTrips(SENDER_UID))
                .thenReturn(List.of());

        mockMvc.perform(get("/favorites/trips")
                        .with(authentication(asSender())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── GET /favorites/package-requests ─────────────────────────────────────

    @Test
    void getPackageRequests_asSender_returns403() throws Exception {
        mockMvc.perform(get("/favorites/package-requests")
                        .with(authentication(asSender())))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPackageRequests_asTraveler_returns200() throws Exception {
        when(favoriteService.getFavoritePackageRequests(TRAVELER_UID))
                .thenReturn(List.of());

        mockMvc.perform(get("/favorites/package-requests")
                        .with(authentication(asTraveler())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── GET /favorites/ids ────────────────────────────────────────────────────

    @Test
    void getIds_authenticated_returns200() throws Exception {
        when(favoriteService.getFavoriteIds(SENDER_UID))
                .thenReturn(new FavoriteIdsResponse(Set.of(), Set.of()));

        mockMvc.perform(get("/favorites/ids")
                        .with(authentication(asSender())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trips").isArray())
                .andExpect(jsonPath("$.packageRequests").isArray());
    }

    // ── Unauthenticated requests ──────────────────────────────────────────────

    @Test
    void putTrip_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/favorites/trip/" + TARGET_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTrips_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/favorites/trips"))
                .andExpect(status().isUnauthorized());
    }
}
