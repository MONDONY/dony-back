package com.dony.api.payments.mobilemoney;

import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.money.CurrencyRegistry;
import com.dony.api.common.money.Money;
import com.dony.api.common.money.MoneyConversion;
import com.dony.api.common.money.PeggedFxRateProvider;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.payments.cash.CashCommissionService;
import com.dony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Task 12 (audit F1/F4/R2) — {@code MobileMoneyPaymentService.initiate} doit charger le
 * NET du bid (jamais {@code declaredValueEur}), résoudre la devise du wallet via
 * {@link com.dony.api.common.money.CountryCurrencies} (jamais "XOF" en dur), et geler le
 * montant local (parité + arrondi transactionnel) au moment de l'initiation (règle R2).
 *
 * <p>Cas de référence (Douala, CM → XAF) : net = 120,00 € ; parité 1 EUR = 655,957 XAF ;
 * 120 × 655,957 = 78 714,84 → HALF_UP → 78 715 → déjà multiple de l'incrément transactionnel
 * 5 → 78 715 (arithmétique vérifiée à la main, cf. rapport de tâche).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MobileMoneyInitiateCurrencyTest {

    @Mock private MobileMoneyPaymentRepository repository;
    @Mock private MobileMoneyGatewayRegistry registry;
    @Mock private MobileMoneyGateway waveGateway;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private ApplicationEventPublisher events;
    @Mock private AuditService auditService;
    @Mock private CashCommissionService cashCommissionService;
    @Mock private PeggedFxRateProvider peggedFxRateProvider;
    @Mock private CurrencyRegistry currencyRegistry;

    private MobileMoneyPaymentService service;

    private final UUID bidId      = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID senderId   = UUID.randomUUID();
    private final UUID annoId     = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MobileMoneyPaymentService(repository, registry, bidRepository,
                announcementRepository, events, auditService, cashCommissionService,
                peggedFxRateProvider, currencyRegistry);

        when(registry.getGateway(PaymentMethod.WAVE)).thenReturn(waveGateway);
        when(registry.isMobileMoneyProvider(PaymentMethod.WAVE)).thenReturn(true);
        when(repository.findTopByBidIdAndDeletedAtIsNullOrderByCreatedAtDesc(bidId))
                .thenReturn(Optional.empty());

        MobileMoneyLinkResult stubResult = new MobileMoneyLinkResult(
                "wave_ref_cm", "https://wave.test/pay?ref=wave_ref_cm",
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30));
        when(waveGateway.generatePaymentLink(any())).thenReturn(stubResult);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void initiate_doualaBid_chargesNetInXafFrozenAndRounded() {
        BidEntity bid = bidWave("CM", "+237600000001");
        bid.setDeclaredValueEur(new BigDecimal("500.00")); // valeur d'assurance — NE DOIT PAS être chargée
        AnnouncementEntity ann = announcement();
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annoId)).thenReturn(Optional.of(ann));

        BigDecimal netEur = new BigDecimal("120.00");
        when(cashCommissionService.computeBidNet(bid, ann)).thenReturn(netEur);

        BigDecimal pegRate = new BigDecimal("655.957");
        when(peggedFxRateProvider.convert(eq(new Money(netEur, "EUR")), eq("XAF")))
                .thenReturn(Optional.of(new MoneyConversion(
                        new Money(netEur, "EUR"),
                        new Money(new BigDecimal("78714.84"), "XAF"),
                        pegRate, "PEGGED")));
        when(currencyRegistry.minorUnitOf("XAF")).thenReturn(0);
        when(currencyRegistry.roundingIncrementOf("XAF")).thenReturn(5);

        ArgumentCaptor<MobileMoneyPaymentEntity> entityCaptor =
                ArgumentCaptor.forClass(MobileMoneyPaymentEntity.class);
        ArgumentCaptor<MobileMoneyPaymentRequest> reqCaptor =
                ArgumentCaptor.forClass(MobileMoneyPaymentRequest.class);

        service.initiate(bidId, senderId);

        org.mockito.Mockito.verify(waveGateway).generatePaymentLink(reqCaptor.capture());
        org.mockito.Mockito.verify(repository).save(entityCaptor.capture());

        MobileMoneyPaymentEntity saved = entityCaptor.getValue();
        assertThat(saved.getAmount()).isEqualByComparingTo("120.00");       // net, PAS declaredValueEur (500.00)
        assertThat(saved.getCurrency()).isEqualTo("XAF");                  // CM → XAF, plus de "XOF" en dur
        assertThat(saved.getAmountMinor()).isEqualTo(78_715L);             // gelé + arrondi transactionnel
        assertThat(saved.getFxRate()).isEqualByComparingTo("655.957");
        assertThat(saved.getRateSource()).isEqualTo("PEGGED");

        MobileMoneyPaymentRequest req = reqCaptor.getValue();
        assertThat(req.currency()).isEqualTo("XAF");
        assertThat(req.amount()).isEqualByComparingTo("78715");            // montant LOCAL, pas le montant EUR

        org.mockito.Mockito.verify(auditService).log(eq("MM_PAYMENT"), eq(bidId), eq("AMOUNT_FROZEN"),
                eq(senderId), any());
    }

    @Test
    void initiate_unsupportedCountry_throwsUnprocessableEntity() {
        BidEntity bid = bidWave("US", "+15551234567"); // hors zone CFA — pas de wallet mobile money
        AnnouncementEntity ann = announcement();
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annoId)).thenReturn(Optional.of(ann));
        when(cashCommissionService.computeBidNet(bid, ann)).thenReturn(new BigDecimal("120.00"));

        assertThatThrownBy(() -> service.initiate(bidId, senderId))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(ex -> {
                    DonyBusinessException e = (DonyBusinessException) ex;
                    assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("unsupported-mm-country");
                });

        org.mockito.Mockito.verify(waveGateway, org.mockito.Mockito.never()).generatePaymentLink(any());
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void initiate_currencyNotConvertible_throwsUnprocessableEntity() {
        BidEntity bid = bidWave("CM", "+237600000001");
        AnnouncementEntity ann = announcement();
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annoId)).thenReturn(Optional.of(ann));
        when(cashCommissionService.computeBidNet(bid, ann)).thenReturn(new BigDecimal("120.00"));
        when(peggedFxRateProvider.convert(any(Money.class), eq("XAF"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiate(bidId, senderId))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(ex -> {
                    DonyBusinessException e = (DonyBusinessException) ex;
                    assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("currency-not-convertible");
                });

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    }

    // --- helpers ---

    private BidEntity bidWave(String countryCode, String phone) {
        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(PaymentMethod.WAVE);
        bid.setMobileMoneyPhone(phone);
        bid.setMobileMoneyCountryCode(countryCode);
        bid.setWeightKg(new BigDecimal("10"));
        bid.setAnnouncementId(annoId);
        bid.setSenderId(senderId);
        try {
            var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(bid, bidId);
        } catch (Exception e) { throw new RuntimeException(e); }
        return bid;
    }

    private AnnouncementEntity announcement() {
        AnnouncementEntity ann = new AnnouncementEntity();
        ann.setTravelerId(travelerId);
        ann.setPricePerKg(new BigDecimal("12"));
        return ann;
    }
}
