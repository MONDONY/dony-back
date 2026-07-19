package com.dony.api.admin;

import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.payments.cash.CashCommissionService;
import com.dony.api.payments.cash.CommissionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AdminCommissionDebtController} — Task 13.
 *
 * Uses @SpringBootTest + MockMvc with inline authentication via
 * SecurityMockMvcRequestPostProcessors.authentication(), bypassing FirebaseTokenFilter,
 * to exercise the real @PreAuthorize("hasRole('ADMIN')") security check.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminCommissionDebtControllerTest — GET /admin/commission-debts")
class AdminCommissionDebtControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    BidRepository bidRepo;

    @MockitoBean
    AnnouncementRepository announcementRepo;

    @MockitoBean
    CashCommissionService cashCommissionService;

    private static UsernamePasswordAuthenticationToken asAdmin() {
        return new UsernamePasswordAuthenticationToken(
                "uid-admin-test", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static UsernamePasswordAuthenticationToken asTraveler() {
        return new UsernamePasswordAuthenticationToken(
                "uid-traveler-test", null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private BidEntity buildFailedBid(UUID id, UUID announcementId, int retryCount, LocalDateTime updatedAt) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", id);
        ReflectionTestUtils.setField(bid, "updatedAt", updatedAt);
        bid.setAnnouncementId(announcementId);
        bid.setCommissionStatus(CommissionStatus.FAILED);
        bid.setCommissionRetryCount(retryCount);
        bid.setCommissionRate(new BigDecimal("0.12"));
        return bid;
    }

    private AnnouncementEntity buildAnnouncement(UUID id, UUID travelerId) {
        AnnouncementEntity ann = new AnnouncementEntity();
        ReflectionTestUtils.setField(ann, "id", id);
        ann.setTravelerId(travelerId);
        return ann;
    }

    @Test
    @DisplayName("GET /admin/commission-debts without ADMIN role → 403")
    void listCommissionDebts_withoutAdminRole_returns403() throws Exception {
        mockMvc.perform(get("/admin/commission-debts")
                        .with(authentication(asTraveler())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/commission-debts unauthenticated → 401")
    void listCommissionDebts_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/admin/commission-debts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /admin/commission-debts with ADMIN role → 200 + expected JSON shape")
    void listCommissionDebts_withAdminRole_returns200() throws Exception {
        UUID bidId = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
        UUID announcementId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID travelerId = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        LocalDateTime failedAt = LocalDateTime.of(2026, 7, 1, 10, 0);

        BidEntity bid = buildFailedBid(bidId, announcementId, 2, failedAt);
        AnnouncementEntity ann = buildAnnouncement(announcementId, travelerId);

        when(bidRepo.findByCommissionStatus(CommissionStatus.FAILED)).thenReturn(List.of(bid));
        when(announcementRepo.findById(announcementId)).thenReturn(Optional.of(ann));
        when(cashCommissionService.computeBidCommission(bid, ann)).thenReturn(new BigDecimal("12.00"));

        mockMvc.perform(get("/admin/commission-debts")
                        .with(authentication(asAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bidId").value(bidId.toString()))
                .andExpect(jsonPath("$[0].travelerId").value(travelerId.toString()))
                .andExpect(jsonPath("$[0].amountOwedEur").value(12.00))
                .andExpect(jsonPath("$[0].retryCount").value(2))
                .andExpect(jsonPath("$[0].failedAt").exists());
    }

    @Test
    @DisplayName("GET /admin/commission-debts — announcement missing → travelerId/amount null, no 500")
    void listCommissionDebts_announcementMissing_returnsNullFields() throws Exception {
        UUID bidId = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        UUID announcementId = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

        BidEntity bid = buildFailedBid(bidId, announcementId, 0, LocalDateTime.now());

        when(bidRepo.findByCommissionStatus(CommissionStatus.FAILED)).thenReturn(List.of(bid));
        when(announcementRepo.findById(announcementId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/commission-debts")
                        .with(authentication(asAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bidId").value(bidId.toString()))
                .andExpect(jsonPath("$[0].travelerId").doesNotExist())
                .andExpect(jsonPath("$[0].amountOwedEur").doesNotExist());
    }

    @Test
    @DisplayName("GET /admin/commission-debts — no debts → empty list")
    void listCommissionDebts_noFailedBids_returnsEmptyList() throws Exception {
        when(bidRepo.findByCommissionStatus(CommissionStatus.FAILED)).thenReturn(List.of());

        mockMvc.perform(get("/admin/commission-debts")
                        .with(authentication(asAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
