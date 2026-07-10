package com.dony.api.matching;

import com.dony.api.payments.events.PaymentReleasedEvent;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("TripsSummaryCacheEvictionListener")
class TripsSummaryCacheEvictionListenerTest {

    @Mock
    private TripsSummaryService tripsSummaryService;

    @Test
    @DisplayName("livraison confirmée → invalide le résumé du voyageur")
    void deliveryConfirmed_evictsTravelerSummary() {
        var listener = new TripsSummaryCacheEvictionListener(tripsSummaryService);
        UUID traveler = UUID.randomUUID();

        listener.onDeliveryConfirmed(
                new DeliveryConfirmedEvent(UUID.randomUUID(), UUID.randomUUID(), traveler));

        verify(tripsSummaryService).evictSummary(traveler);
    }

    @Test
    @DisplayName("paiement libéré → invalide le résumé du voyageur")
    void paymentReleased_evictsTravelerSummary() {
        var listener = new TripsSummaryCacheEvictionListener(tripsSummaryService);
        UUID traveler = UUID.randomUUID();

        listener.onPaymentReleased(new PaymentReleasedEvent(
                UUID.randomUUID(), traveler, UUID.randomUUID(), BigDecimal.TEN));

        verify(tripsSummaryService).evictSummary(traveler);
    }
}
