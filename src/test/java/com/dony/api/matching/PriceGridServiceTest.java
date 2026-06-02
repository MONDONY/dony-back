package com.dony.api.matching;

import com.dony.api.common.AuditService;
import com.dony.api.matching.dto.PriceGridItemRequest;
import com.dony.api.matching.dto.PriceGridItemResponse;
import com.dony.api.common.CommissionRateResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PriceGridServiceTest {

    @Mock PriceGridItemRepository gridRepo;
    @Mock AnnouncementPriceGridItemRepository annGridRepo;
    @Mock AuditService auditService;
    @Mock CommissionRateResolver commissionRateResolver;

    PriceGridService service;

    @BeforeEach
    void setUp() {
        service = new PriceGridService(gridRepo, annGridRepo, auditService, commissionRateResolver);
    }

    @Test
    void addItem_saves_and_returns_response_with_display_price() {
        UUID travelerId = UUID.randomUUID();
        when(commissionRateResolver.resolve(any())).thenReturn(new BigDecimal("0.12"));
        PriceGridItemRequest req = new PriceGridItemRequest("Valise cabine", new BigDecimal("10.00"));

        PriceGridItemEntity saved = new PriceGridItemEntity();
        saved.setLabel("Valise cabine");
        saved.setUnitPriceNet(new BigDecimal("10.00"));
        saved.setPosition(0);

        when(gridRepo.countByTravelerId(travelerId)).thenReturn(0L);
        when(gridRepo.save(any())).thenReturn(saved);

        PriceGridItemResponse response = service.addItem(travelerId, req, travelerId);

        assertThat(response.label()).isEqualTo("Valise cabine");
        assertThat(response.unitPriceNet()).isEqualByComparingTo("10.00");
        assertThat(response.unitPriceDisplay()).isEqualByComparingTo("11.20");
        verify(gridRepo).save(any(PriceGridItemEntity.class));
    }

    @Test
    void displayPrice_derivesFromResolvedCommissionRate() {
        // SOURCE UNIQUE : le taux vient du resolver (override utilisateur / dony.commission.rate).
        // À 20 % : 10,00 € net → 12,00 € affiché.
        when(commissionRateResolver.resolve(any())).thenReturn(new BigDecimal("0.20"));
        assertThat(service.displayPrice(new BigDecimal("10.00"), null)).isEqualByComparingTo("12.00");
    }

    @Test
    void addItem_rejects_when_limit_reached() {
        UUID travelerId = UUID.randomUUID();
        when(gridRepo.countByTravelerId(travelerId)).thenReturn(20L);

        assertThatThrownBy(() ->
            service.addItem(travelerId, new PriceGridItemRequest("X", BigDecimal.ONE), travelerId)
        ).isInstanceOf(ResponseStatusException.class)
         .hasMessageContaining("price-grid-limit");
    }

    @Test
    void snapshotToAnnouncement_copies_items_immutably() {
        UUID travelerId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();

        PriceGridItemEntity item = new PriceGridItemEntity();
        item.setLabel("Carton");
        item.setUnitPriceNet(new BigDecimal("15.00"));
        item.setPosition(0);

        when(gridRepo.findByTravelerIdOrderByPositionAsc(travelerId)).thenReturn(List.of(item));

        service.snapshotToAnnouncement(travelerId, announcementId);

        verify(annGridRepo).saveAll(argThat(items -> {
            var list = (java.util.List<AnnouncementPriceGridItemEntity>) items;
            AnnouncementPriceGridItemEntity snap = list.get(0);
            return snap.getLabel().equals("Carton")
                && snap.getUnitPriceNet().compareTo(new BigDecimal("15.00")) == 0
                && snap.getAnnouncementId().equals(announcementId);
        }));
    }

    @Test
    void snapshotToAnnouncement_throws_422_when_grid_empty() {
        UUID travelerId = UUID.randomUUID();
        when(gridRepo.findByTravelerIdOrderByPositionAsc(travelerId)).thenReturn(List.of());

        assertThatThrownBy(() ->
            service.snapshotToAnnouncement(travelerId, UUID.randomUUID())
        ).isInstanceOf(ResponseStatusException.class)
         .satisfies(ex -> assertThat(((ResponseStatusException)ex).getStatusCode())
             .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void displayPrice_multiplies_by_resolved_rate_with_half_up_rounding() {
        when(commissionRateResolver.resolve(any())).thenReturn(new BigDecimal("0.12"));
        assertThat(service.displayPrice(new BigDecimal("10.00"), null)).isEqualByComparingTo("11.20");
        assertThat(service.displayPrice(new BigDecimal("7.00"), null)).isEqualByComparingTo("7.84");
        // 41.00 × 1.12 = 45.92 (used in spec acceptance criteria)
        assertThat(service.displayPrice(new BigDecimal("41.00"), null)).isEqualByComparingTo("45.92");
    }
}
