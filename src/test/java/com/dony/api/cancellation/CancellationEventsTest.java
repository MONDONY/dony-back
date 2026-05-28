package com.dony.api.cancellation;

import com.dony.api.cancellation.events.TravelerHighCancellationEvent;
import com.dony.api.cancellation.events.TripCancelledEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationEventsTest {

    @Test
    void travelerHighCancellationEvent_getters_returnConstructorValues() {
        UUID travelerId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();

        TravelerHighCancellationEvent event = new TravelerHighCancellationEvent(travelerId, 3, announcementId);

        assertThat(event.getTravelerId()).isEqualTo(travelerId);
        assertThat(event.getCancellationCount()).isEqualTo(3);
        assertThat(event.getAnnouncementId()).isEqualTo(announcementId);
    }

    @Test
    void tripCancelledEvent_getters_returnConstructorValues() {
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(
                announcementId, travelerId, List.of(), "SICK", List.of(bidId));

        assertThat(event.getAnnouncementId()).isEqualTo(announcementId);
        assertThat(event.getTravelerId()).isEqualTo(travelerId);
        assertThat(event.getReason()).isEqualTo("SICK");
        assertThat(event.getAffectedBidIds()).containsExactly(bidId);
        assertThat(event.getAffectedSenderIds()).isEmpty();
        // Backward-compat constructor yields empty map
        assertThat(event.getBidPaymentMethods()).isEmpty();
    }

    @Test
    void tripCancelledEvent_withBidPaymentMethods_getterReturnsMap() {
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        Map<UUID, String> methods = Map.of(bidId, "CASH");

        TripCancelledEvent event = new TripCancelledEvent(
                announcementId, travelerId, List.of(), "SICK", List.of(bidId), methods);

        assertThat(event.getBidPaymentMethods()).containsEntry(bidId, "CASH");
    }

    @Test
    void tripCancelledEvent_nullBidPaymentMethods_defaultsToEmptyMap() {
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(
                announcementId, travelerId, List.of(), "SICK", List.of(), null);

        assertThat(event.getBidPaymentMethods()).isEmpty();
    }
}
