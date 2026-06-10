package com.dony.api.payments;

import com.dony.api.matching.AnnouncementService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrat règle critique #18 (CLAUDE.md) : tout listener de paiement doit être
 * annoté {@code @TransactionalEventListener(phase = AFTER_COMMIT)} +
 * {@code @Transactional(propagation = REQUIRES_NEW)}. Un simple
 * {@code @EventListener} lit des données non commitées et peut déclencher un
 * remboursement Stripe pour une transaction métier finalement rollbackée.
 */
class PaymentListenerTransactionalContractTest {

    static Stream<Arguments> paymentListeners() {
        return Stream.of(
                Arguments.of(BidRejectedEventListener.class, "handleBidRejected"),
                Arguments.of(ParcelRefusedEventListener.class, "onParcelRefused"),
                Arguments.of(NoShowEventListener.class, "onVoyageurNoShow"),
                Arguments.of(BidExpiredOnDepartureEventListener.class, "handleBidExpired"),
                Arguments.of(TripCancelledEventListener.class, "handleTripCancelled"),
                Arguments.of(DeliveryEventListener.class, "handleDeliveryConfirmed"),
                Arguments.of(NegotiationCaptureListener.class, "onEscrowReady"),
                Arguments.of(BidAcceptedEventListener.class, "onBidAccepted")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paymentListeners")
    void payment_listener_uses_after_commit_and_requires_new(Class<?> listenerClass,
                                                             String methodName) {
        Method handler = Arrays.stream(listenerClass.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        listenerClass.getSimpleName() + "#" + methodName + " introuvable"));

        TransactionalEventListener tel = handler.getAnnotation(TransactionalEventListener.class);
        assertThat(tel)
                .as("%s#%s doit être @TransactionalEventListener", listenerClass.getSimpleName(), methodName)
                .isNotNull();
        assertThat(tel.phase())
                .as("%s#%s doit écouter en AFTER_COMMIT", listenerClass.getSimpleName(), methodName)
                .isEqualTo(TransactionPhase.AFTER_COMMIT);

        Transactional tx = handler.getAnnotation(Transactional.class);
        assertThat(tx)
                .as("%s#%s doit être @Transactional", listenerClass.getSimpleName(), methodName)
                .isNotNull();
        assertThat(tx.propagation())
                .as("%s#%s doit utiliser REQUIRES_NEW", listenerClass.getSimpleName(), methodName)
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void trigger_in_progress_transitions_is_transactional() throws NoSuchMethodException {
        // BidExpiredOnDepartureEvent est écouté en AFTER_COMMIT : publié hors
        // transaction (chemin scheduler), l'event serait silencieusement perdu et
        // les remboursements des bids expirés jamais déclenchés.
        Method m = AnnouncementService.class.getMethod("triggerInProgressTransitions");
        assertThat(m.getAnnotation(Transactional.class))
                .as("AnnouncementService#triggerInProgressTransitions doit être @Transactional "
                        + "(publisher d'un event écouté en AFTER_COMMIT)")
                .isNotNull();
    }
}
