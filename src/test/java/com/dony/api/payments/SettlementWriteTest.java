package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.payments.mobilemoney.MobileMoneyGateway;
import com.dony.api.payments.mobilemoney.MobileMoneyGatewayRegistry;
import com.dony.api.payments.mobilemoney.MobileMoneyPaymentEntity;
import com.dony.api.payments.mobilemoney.MobileMoneyPaymentRepository;
import com.dony.api.payments.mobilemoney.MobileMoneyPaymentService;
import com.dony.api.payments.mobilemoney.events.BidPaidByMobileMoneyEvent;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Task 11 — écriture du règlement.
 *
 * <p>Ce test couvre UNIQUEMENT le volet webhook Mobile Money (settledAmountMinor). Le volet
 * capture Stripe ({@code PaymentRepository.markCapturedIfEscrow}) n'est PAS implémenté ici :
 * voir {@code .superpowers/sdd/task-11-report.md} pour le détail du blocage — la méthode est en
 * réalité une requête JPQL bulk-UPDATE atomique appelée depuis deux listeners différents
 * (BidAcceptedEventListener, NegotiationCaptureListener), et non une méthode PaymentService
 * comme le brief le supposait. Poser les 4 champs settlement_* sur l'entité en mémoire puis
 * appeler {@code paymentRepository.save(...)} risquerait d'écraser {@code captured_at} (mis à
 * jour par la requête bulk, donc invisible à l'entité chargée en mémoire) — ce qui casserait la
 * protection anti double-capture. Escaladé plutôt que deviné, conformément aux consignes de la
 * tâche.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettlementWriteTest {

    @Mock private MobileMoneyPaymentRepository mmRepository;
    @Mock private MobileMoneyGatewayRegistry mmRegistry;
    @Mock private MobileMoneyGateway waveGateway;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private ApplicationEventPublisher events;
    @Mock private AuditService auditService;

    @Test
    void mobileMoneyWebhook_confirmed_setsSettledAmountMinorFromFrozenAmount() {
        MobileMoneyPaymentService service = new MobileMoneyPaymentService(
                mmRepository, mmRegistry, bidRepository, announcementRepository, events, auditService);

        String payload = "{\"reference\":\"wave_ref_settlement\",\"status\":\"SUCCEEDED\"}";
        String signature = "sig";

        MobileMoneyPaymentEntity payment = new MobileMoneyPaymentEntity();
        payment.setBidId(UUID.randomUUID());
        payment.setTravelerId(UUID.randomUUID());
        payment.setStatus("PENDING");
        payment.setAmountMinor(5_000L); // montant gelé à l'initiation (règle R2)

        when(mmRegistry.getGateway(PaymentMethod.WAVE)).thenReturn(waveGateway);
        when(waveGateway.verifyWebhookSignature(payload, signature)).thenReturn(true);
        when(waveGateway.extractExternalReference(payload)).thenReturn("wave_ref_settlement");
        when(waveGateway.isPaymentConfirmed(payload)).thenReturn(true);
        when(mmRepository.findByExternalReference("wave_ref_settlement")).thenReturn(Optional.of(payment));
        when(mmRepository.save(payment)).thenReturn(payment);

        service.handleWebhook(PaymentMethod.WAVE, payload, signature);

        assertThat(payment.getStatus()).isEqualTo("COMPLETED");
        assertThat(payment.getSettledAmountMinor()).isEqualTo(5_000L);
        assertThat(payment.getSettledAmountMinor()).isEqualTo(payment.getAmountMinor());
    }

    @Test
    void mobileMoneyWebhook_notConfirmed_doesNotSetSettledAmountMinor() {
        MobileMoneyPaymentService service = new MobileMoneyPaymentService(
                mmRepository, mmRegistry, bidRepository, announcementRepository, events, auditService);

        String payload = "{\"reference\":\"wave_ref_fail\",\"status\":\"FAILED\"}";
        String signature = "sig";

        MobileMoneyPaymentEntity payment = new MobileMoneyPaymentEntity();
        payment.setBidId(UUID.randomUUID());
        payment.setTravelerId(UUID.randomUUID());
        payment.setStatus("PENDING");
        payment.setAmountMinor(5_000L);

        when(mmRegistry.getGateway(PaymentMethod.WAVE)).thenReturn(waveGateway);
        when(waveGateway.verifyWebhookSignature(payload, signature)).thenReturn(true);
        when(waveGateway.extractExternalReference(payload)).thenReturn("wave_ref_fail");
        when(waveGateway.isPaymentConfirmed(payload)).thenReturn(false);
        when(waveGateway.extractFailureReason(payload)).thenReturn("insufficient_funds");
        when(mmRepository.findByExternalReference("wave_ref_fail")).thenReturn(Optional.of(payment));
        when(mmRepository.save(payment)).thenReturn(payment);

        service.handleWebhook(PaymentMethod.WAVE, payload, signature);

        assertThat(payment.getStatus()).isEqualTo("FAILED");
        assertThat(payment.getSettledAmountMinor()).isNull();
    }
}
