package com.dony.api.promo;

import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromoServiceTest {

    @Mock PromoCodeRepository promoCodeRepository;
    @Mock PromoRedemptionRepository redemptionRepository;
    @Mock AuditService auditService;

    PromoService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PromoService(promoCodeRepository, redemptionRepository, auditService);
    }

    private PromoCodeEntity activePromo(BigDecimal rate) {
        PromoCodeEntity p = new PromoCodeEntity();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setCode("WELCOME10");
        p.setRate(rate);
        p.setTarget(PromoCodeTarget.ANY);
        p.setStatus(PromoCodeStatus.ACTIVE);
        p.setPerUserLimit(1);
        return p;
    }

    @Nested
    @DisplayName("validateAndGetRate()")
    class ValidateTests {

        @Test
        void valid_code_returns_rate() {
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(activePromo(new BigDecimal("0.06"))));
            when(redemptionRepository.countByPromoCodeIdAndUserId(any(), any())).thenReturn(0L);

            BigDecimal rate = service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER);

            assertThat(rate).isEqualByComparingTo("0.06");
        }

        @Test
        void unknown_code_throws_promo_not_found() {
            when(promoCodeRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.validateAndGetRate("UNKNOWN", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("promo-not-found"));
        }

        @Test
        void disabled_code_throws_promo_expired() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setStatus(PromoCodeStatus.DISABLED);
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("promo-expired"));
        }

        @Test
        void expired_validTo_throws_promo_expired() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setValidTo(LocalDateTime.now().minusDays(1));
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("promo-expired"));
        }

        @Test
        void future_validFrom_throws_promo_expired() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setValidFrom(LocalDateTime.now().plusDays(1));
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("promo-expired"));
        }

        @Test
        void max_redemptions_reached_throws_promo_limit_reached() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setMaxRedemptions(100);
            p.setRedeemedCount(100);
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("promo-limit-reached"));
        }

        @Test
        void per_user_limit_exceeded_throws_promo_limit_reached() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(redemptionRepository.countByPromoCodeIdAndUserId(any(), eq(userId))).thenReturn(1L); // already used once, limit = 1

            assertThatThrownBy(() -> service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("promo-limit-reached"));
        }

        @Test
        void traveler_target_rejects_sender() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setTarget(PromoCodeTarget.TRAVELER);
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("promo-not-eligible");
                        assertThat(((DonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    });
        }

        @Test
        void any_target_accepts_sender_and_traveler() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            p.setTarget(PromoCodeTarget.ANY);
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(redemptionRepository.countByPromoCodeIdAndUserId(any(), any())).thenReturn(0L);

            assertThat(service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.SENDER)).isEqualByComparingTo("0.06");
            assertThat(service.validateAndGetRate("WELCOME10", userId, PromoCodeTarget.TRAVELER)).isEqualByComparingTo("0.06");
        }
    }

    @Nested
    @DisplayName("redeem()")
    class RedeemTests {

        @Test
        void first_redemption_increments_count_and_saves() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            UUID promoId = (UUID) ReflectionTestUtils.getField(p, "id");
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(redemptionRepository.existsByPromoCodeIdAndBidId(promoId, bidId)).thenReturn(false);
            when(promoCodeRepository.findByIdForUpdate(promoId)).thenReturn(Optional.of(p));
            when(promoCodeRepository.save(p)).thenReturn(p);
            when(redemptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.redeem("WELCOME10", userId, bidId, new BigDecimal("0.06"));

            assertThat(p.getRedeemedCount()).isEqualTo(1);
            verify(redemptionRepository).save(any(PromoRedemptionEntity.class));
            verify(auditService).log(eq("PROMO"), any(), eq("PROMO_CODE_REDEEMED"), eq(userId), any());
        }

        @Test
        void idempotent_skip_if_already_redeemed_for_bid() {
            PromoCodeEntity p = activePromo(new BigDecimal("0.06"));
            UUID promoId = (UUID) ReflectionTestUtils.getField(p, "id");
            when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(p));
            when(redemptionRepository.existsByPromoCodeIdAndBidId(promoId, bidId)).thenReturn(true);

            PromoRedemptionEntity existing = new PromoRedemptionEntity();
            when(redemptionRepository.findByPromoCodeIdAndBidId(promoId, bidId)).thenReturn(Optional.of(existing));

            PromoRedemptionEntity result = service.redeem("WELCOME10", userId, bidId, new BigDecimal("0.06"));

            assertThat(result).isSameAs(existing);
            verify(promoCodeRepository, never()).findByIdForUpdate(any());
            verify(redemptionRepository, never()).save(any());
        }
    }
}
