package com.dony.api.payments.mobilemoney;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.payments.mobilemoney.events.BidPaidByMobileMoneyEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MobileMoneyPaymentServiceTest {

    @Mock private MobileMoneyPaymentRepository repository;
    @Mock private MobileMoneyGatewayRegistry registry;
    @Mock private MobileMoneyGateway waveGateway;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private ApplicationEventPublisher events;

    @InjectMocks private MobileMoneyPaymentService service;

    private final UUID bidId       = UUID.randomUUID();
    private final UUID travelerId  = UUID.randomUUID();
    private final UUID annoId      = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(registry.getGateway(PaymentMethod.WAVE)).thenReturn(waveGateway);
    }

    @Test
    void initiate_validWaveBid_savesEntityAndReturnsLink() {
        BidEntity bid = waveBid();
        AnnouncementEntity ann = announcement();
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annoId)).thenReturn(Optional.of(ann));
        when(repository.findTopByBidIdAndDeletedAtIsNullOrderByCreatedAtDesc(bidId)).thenReturn(Optional.empty());

        MobileMoneyLinkResult stubResult = new MobileMoneyLinkResult(
                "wave_ref_123", "https://wave.test/pay?ref=wave_ref_123",
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30));
        when(waveGateway.generatePaymentLink(any())).thenReturn(stubResult);

        ArgumentCaptor<MobileMoneyPaymentEntity> captor = ArgumentCaptor.forClass(MobileMoneyPaymentEntity.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        MobileMoneyPaymentEntity result = service.initiate(bidId);

        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getExternalReference()).isEqualTo("wave_ref_123");
        assertThat(result.getTravelerId()).isEqualTo(travelerId);
    }

    @Test
    void initiate_existingPendingNotExpired_returnsExistingWithoutNewGatewayCall() {
        BidEntity bid = waveBid();
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        MobileMoneyPaymentEntity existing = new MobileMoneyPaymentEntity();
        existing.setBidId(bidId);
        existing.setStatus("PENDING");
        existing.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(25));
        when(repository.findTopByBidIdAndDeletedAtIsNullOrderByCreatedAtDesc(bidId))
                .thenReturn(Optional.of(existing));

        MobileMoneyPaymentEntity result = service.initiate(bidId);

        assertThat(result).isSameAs(existing);
        verify(waveGateway, never()).generatePaymentLink(any());
    }

    @Test
    void handleWebhook_validSignatureConfirmed_marksCompletedAndPublishesEvent() {
        String payload = "{\"reference\":\"wave_ref_xyz\",\"status\":\"SUCCEEDED\"}";
        String signature = "some-valid-sig";

        MobileMoneyPaymentEntity payment = new MobileMoneyPaymentEntity();
        payment.setBidId(bidId);
        payment.setTravelerId(travelerId);
        payment.setStatus("PENDING");

        when(waveGateway.verifyWebhookSignature(payload, signature)).thenReturn(true);
        when(waveGateway.extractExternalReference(payload)).thenReturn("wave_ref_xyz");
        when(waveGateway.isPaymentConfirmed(payload)).thenReturn(true);
        when(waveGateway.extractFailureReason(payload)).thenReturn(null);
        when(repository.findByExternalReference("wave_ref_xyz")).thenReturn(Optional.of(payment));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handleWebhook(PaymentMethod.WAVE, payload, signature);

        assertThat(payment.getStatus()).isEqualTo("COMPLETED");
        verify(events).publishEvent(any(BidPaidByMobileMoneyEvent.class));
    }

    @Test
    void handleWebhook_invalidSignature_throwsUnauthorized() {
        when(waveGateway.verifyWebhookSignature(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.handleWebhook(PaymentMethod.WAVE, "{}", "bad-sig"))
                .isInstanceOf(DonyBusinessException.class);
    }

    @Test
    void handleWebhook_alreadyCompleted_idempotentNoEvent() {
        String payload = "{\"reference\":\"wave_ref_abc\",\"status\":\"SUCCEEDED\"}";
        when(waveGateway.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(waveGateway.extractExternalReference(payload)).thenReturn("wave_ref_abc");

        MobileMoneyPaymentEntity payment = new MobileMoneyPaymentEntity();
        payment.setStatus("COMPLETED");
        when(repository.findByExternalReference("wave_ref_abc")).thenReturn(Optional.of(payment));

        service.handleWebhook(PaymentMethod.WAVE, payload, "sig");

        verify(events, never()).publishEvent(any());
        verify(repository, never()).save(any());
    }

    @Test
    void getStatus_delegatesToRepository() {
        when(repository.findTopByBidIdAndDeletedAtIsNullOrderByCreatedAtDesc(bidId))
                .thenReturn(Optional.empty());

        Optional<MobileMoneyPaymentEntity> result = service.getStatus(bidId);

        assertThat(result).isEmpty();
    }

    private BidEntity waveBid() {
        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(PaymentMethod.WAVE);
        bid.setMobileMoneyPhone("+2250700000001");
        bid.setMobileMoneyCountryCode("CI");
        bid.setDeclaredValueEur(new BigDecimal("50.00"));
        bid.setAnnouncementId(annoId);
        // Injecter l'ID via réflexion (BaseEntity champ privé)
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
        return ann;
    }
}
