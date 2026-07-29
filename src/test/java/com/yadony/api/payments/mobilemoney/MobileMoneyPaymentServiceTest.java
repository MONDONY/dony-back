package com.yadony.api.payments.mobilemoney;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.mobilemoney.events.BidPaidByMobileMoneyEvent;
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
    @Mock private AuditService auditService;

    @InjectMocks private MobileMoneyPaymentService service;

    private final UUID bidId       = UUID.randomUUID();
    private final UUID travelerId  = UUID.randomUUID();
    private final UUID senderId    = UUID.randomUUID();
    private final UUID annoId      = UUID.randomUUID();

    // --- initiate() : le paiement mobile money direct par l'expéditeur a été
    // retiré (cf. BidService : 422 mobile-money-bid-payment-retired à la
    // création du bid). initiate() reste exposée pour d'éventuels bids
    // WAVE/ORANGE_MONEY legacy encore PENDING, mais renvoie désormais
    // systématiquement la même erreur au lieu de générer un lien de paiement.

    @Test
    void initiateAlwaysRejectsAsRetired() {
        BidEntity bid = new BidEntity();
        bid.setSenderId(senderId);
        bid.setPaymentMethod(PaymentMethod.WAVE);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> service.initiate(bidId, senderId))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("plus disponible");

        verifyNoInteractions(registry);
    }

    @Test
    void initiate_callerIsNotSender_throwsForbidden() {
        BidEntity bid = waveBid();
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        UUID randomCaller = UUID.randomUUID();
        assertThatThrownBy(() -> service.initiate(bidId, randomCaller))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));

        verifyNoInteractions(registry);
    }

    @Test
    void handleWebhook_validSignatureConfirmed_marksCompletedAndPublishesEvent() {
        when(registry.getGateway(PaymentMethod.WAVE)).thenReturn(waveGateway);
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
        verify(auditService).log(eq("MM_PAYMENT"), any(), eq("PAYMENT_COMPLETED"),
                eq(travelerId), any());
    }

    @Test
    void handleWebhook_invalidSignature_throwsUnauthorized() {
        when(registry.getGateway(PaymentMethod.WAVE)).thenReturn(waveGateway);
        when(waveGateway.verifyWebhookSignature(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.handleWebhook(PaymentMethod.WAVE, "{}", "bad-sig"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED));
    }

    @Test
    void handleWebhook_alreadyCompleted_idempotentNoEvent() {
        when(registry.getGateway(PaymentMethod.WAVE)).thenReturn(waveGateway);
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
    void getStatus_senderCanView() {
        BidEntity bid = waveBid();
        AnnouncementEntity ann = announcement();
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annoId)).thenReturn(Optional.of(ann));
        when(repository.findTopByBidIdAndDeletedAtIsNullOrderByCreatedAtDesc(bidId))
                .thenReturn(Optional.empty());

        Optional<MobileMoneyPaymentEntity> result = service.getStatus(bidId, senderId);

        assertThat(result).isEmpty();
    }

    @Test
    void getStatus_travelerCanView() {
        BidEntity bid = waveBid();
        AnnouncementEntity ann = announcement();
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annoId)).thenReturn(Optional.of(ann));
        when(repository.findTopByBidIdAndDeletedAtIsNullOrderByCreatedAtDesc(bidId))
                .thenReturn(Optional.empty());

        Optional<MobileMoneyPaymentEntity> result = service.getStatus(bidId, travelerId);

        assertThat(result).isEmpty();
    }

    @Test
    void handleWebhook_paymentFailed_marksFailedAndLogsAudit() {
        when(registry.getGateway(PaymentMethod.WAVE)).thenReturn(waveGateway);
        String payload = "{\"reference\":\"wave_ref_fail\",\"status\":\"FAILED\",\"failure_reason\":\"insufficient funds\"}";
        String signature = "sig";

        MobileMoneyPaymentEntity payment = new MobileMoneyPaymentEntity();
        payment.setBidId(bidId);
        payment.setTravelerId(travelerId);
        payment.setStatus("PENDING");

        when(waveGateway.verifyWebhookSignature(payload, signature)).thenReturn(true);
        when(waveGateway.extractExternalReference(payload)).thenReturn("wave_ref_fail");
        when(waveGateway.isPaymentConfirmed(payload)).thenReturn(false);
        when(waveGateway.extractFailureReason(payload)).thenReturn("insufficient funds");
        when(repository.findByExternalReference("wave_ref_fail")).thenReturn(Optional.of(payment));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handleWebhook(PaymentMethod.WAVE, payload, signature);

        assertThat(payment.getStatus()).isEqualTo("FAILED");
        assertThat(payment.getFailureReason()).isEqualTo("insufficient funds");
        verify(events, never()).publishEvent(any(BidPaidByMobileMoneyEvent.class));
    }

    @Test
    void handleWebhook_nullReference_returnsEarly() {
        when(registry.getGateway(PaymentMethod.WAVE)).thenReturn(waveGateway);
        String payload = "{\"no_reference\":true}";
        String signature = "sig";

        when(waveGateway.verifyWebhookSignature(payload, signature)).thenReturn(true);
        when(waveGateway.extractExternalReference(payload)).thenReturn(null);

        service.handleWebhook(PaymentMethod.WAVE, payload, signature);

        verify(repository, never()).findByExternalReference(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void handleWebhook_paymentNotFound_returnsEarly() {
        when(registry.getGateway(PaymentMethod.WAVE)).thenReturn(waveGateway);
        String payload = "{\"reference\":\"unknown_ref\",\"status\":\"SUCCEEDED\"}";
        String signature = "sig";

        when(waveGateway.verifyWebhookSignature(payload, signature)).thenReturn(true);
        when(waveGateway.extractExternalReference(payload)).thenReturn("unknown_ref");
        when(repository.findByExternalReference("unknown_ref")).thenReturn(Optional.empty());

        service.handleWebhook(PaymentMethod.WAVE, payload, signature);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void getStatus_pendingExpiredPayment_marksExpiredAndReturns() {
        BidEntity bid = waveBid();
        AnnouncementEntity ann = announcement();
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annoId)).thenReturn(Optional.of(ann));

        MobileMoneyPaymentEntity payment = new MobileMoneyPaymentEntity();
        payment.setBidId(bidId);
        payment.setStatus("PENDING");
        payment.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        when(repository.findTopByBidIdAndDeletedAtIsNullOrderByCreatedAtDesc(bidId))
                .thenReturn(Optional.of(payment));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<MobileMoneyPaymentEntity> result = service.getStatus(bidId, senderId);

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo("EXPIRED");
        verify(repository).save(payment);
    }

    @Test
    void getStatus_randomCallerThrowsForbidden() {
        BidEntity bid = waveBid();
        AnnouncementEntity ann = announcement();
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annoId)).thenReturn(Optional.of(ann));

        UUID randomCaller = UUID.randomUUID();
        assertThatThrownBy(() -> service.getStatus(bidId, randomCaller))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));
    }

    private BidEntity waveBid() {
        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(PaymentMethod.WAVE);
        bid.setMobileMoneyPhone("+2250700000001");
        bid.setMobileMoneyCountryCode("CI");
        bid.setAnnouncementId(annoId);
        bid.setSenderId(senderId);
        // Injecter l'ID via réflexion (BaseEntity champ privé)
        try {
            var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
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
