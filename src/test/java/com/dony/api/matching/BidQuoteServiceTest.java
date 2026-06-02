package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.auth.BlockService;
import com.dony.api.cancellation.CancellationRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.CommissionRateResolver;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.dto.BidQuoteRequest;
import com.dony.api.matching.dto.BidQuoteResponse;
import com.dony.api.promo.PromoService;
import com.dony.api.ratings.RatingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidQuoteServiceTest {

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock RatingRepository ratingRepository;
    @Mock CancellationRepository cancellationRepository;
    @Mock BidGridItemRepository bidGridItemRepository;
    @Mock AnnouncementPriceGridItemRepository annGridItemRepository;
    @Mock BlockService blockService;
    @Mock CommissionRateResolver commissionRateResolver;
    @Mock PromoService promoService;

    @InjectMocks BidService bidService;

    private static final String SENDER_UID = "uid-sender";
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID ANN_ID = UUID.randomUUID();

    private UserEntity sender() {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", SENDER_ID);
        u.setFirebaseUid(SENDER_UID);
        return u;
    }

    private AnnouncementEntity announcement() {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", ANN_ID);
        a.setTravelerId(TRAVELER_ID);
        a.setPricePerKg(new BigDecimal("20.00"));
        a.setPricingMode(PricingMode.KG);
        return a;
    }

    @Test
    @DisplayName("sans promo : total = net × (1 + globalRate)")
    void quote_noPromo_usesGlobalRate() {
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement()));
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID)).thenReturn(new BigDecimal("0.12"));

        BidQuoteRequest req = new BidQuoteRequest(ANN_ID, new BigDecimal("5"), null);
        BidQuoteResponse resp = bidService.quote(SENDER_UID, req);

        assertThat(resp.netEur()).isEqualByComparingTo("100.00"); // 5 × 20
        assertThat(resp.rate()).isEqualByComparingTo("0.12");
        assertThat(resp.commissionEur()).isEqualByComparingTo("12.00");
        assertThat(resp.totalEur()).isEqualByComparingTo("112.00");
        assertThat(resp.promoApplied()).isFalse();
        assertThat(resp.promoLabel()).isNull();
    }

    @Test
    @DisplayName("avec promo valide : taux réduit, promoApplied=true, label présent")
    void quote_withPromo_appliesPromoRate() {
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement()));
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID, "PROMO6")).thenReturn(new BigDecimal("0.06"));

        BidQuoteRequest req = new BidQuoteRequest(ANN_ID, new BigDecimal("5"), "PROMO6");
        BidQuoteResponse resp = bidService.quote(SENDER_UID, req);

        assertThat(resp.netEur()).isEqualByComparingTo("100.00");
        assertThat(resp.rate()).isEqualByComparingTo("0.06");
        assertThat(resp.commissionEur()).isEqualByComparingTo("6.00");
        assertThat(resp.totalEur()).isEqualByComparingTo("106.00");
        assertThat(resp.promoApplied()).isTrue();
        assertThat(resp.promoLabel()).contains("PROMO6");
    }

    @Test
    @DisplayName("promo invalide : DonyBusinessException propagée (pas de fallback silencieux)")
    void quote_invalidPromo_throwsException() {
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement()));
        when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID, "EXPIRED"))
                .thenThrow(new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "promo-expired", "Promo Expired", "Ce code promo a expiré"));

        BidQuoteRequest req = new BidQuoteRequest(ANN_ID, new BigDecimal("5"), "EXPIRED");

        assertThatThrownBy(() -> bidService.quote(SENDER_UID, req))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("promo-expired"));
    }

    @Test
    @DisplayName("annonce introuvable → 404")
    void quote_announcementNotFound_throws404() {
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender()));
        when(announcementRepository.findById(any())).thenReturn(Optional.empty());

        BidQuoteRequest req = new BidQuoteRequest(UUID.randomUUID(), new BigDecimal("5"), null);

        assertThatThrownBy(() -> bidService.quote(SENDER_UID, req))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
