package com.yadony.api.disputes;

import com.yadony.api.disputes.events.DisputeOpenedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DisputeOpenedEventListenerTest {

    @Mock DisputeService disputeService;
    DisputeOpenedEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new DisputeOpenedEventListener(disputeService);
    }

    @Test
    void defaultConstructor_routesToSenderNoShow() {
        UUID bidId = UUID.randomUUID(), senderId = UUID.randomUUID(), travelerId = UUID.randomUUID();
        listener.handleDisputeOpened(new DisputeOpenedEvent(bidId, senderId, travelerId));

        verify(disputeService).openSenderNoShowDispute(bidId, senderId, travelerId);
        verify(disputeService, never()).openDeliveryNoShowDispute(any(), any(), any(), any());
    }

    @Test
    void deliveryType_routesToDeliveryDispute() {
        UUID bidId = UUID.randomUUID(), senderId = UUID.randomUUID(), travelerId = UUID.randomUUID();
        listener.handleDisputeOpened(
                new DisputeOpenedEvent(bidId, senderId, travelerId, "RECIPIENT_NO_SHOW_CONTESTED"));

        verify(disputeService).openDeliveryNoShowDispute(
                bidId, senderId, travelerId, "RECIPIENT_NO_SHOW_CONTESTED");
        verify(disputeService, never()).openSenderNoShowDispute(any(), any(), any());
    }
}
