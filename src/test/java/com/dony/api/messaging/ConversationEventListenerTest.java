package com.dony.api.messaging;

import com.dony.api.matching.events.BidAcceptedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationEventListenerTest {

    @Mock ConversationService conversationService;
    @InjectMocks ConversationEventListener listener;

    @Test
    void handleBidAccepted_callsCreateConversation() {
        UUID bidId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidAcceptedEvent event = new BidAcceptedEvent(bidId, senderId, travelerId, UUID.randomUUID());

        listener.handleBidAccepted(event);

        verify(conversationService).createConversationForBid(bidId, senderId, travelerId);
    }

    @Test
    void handleBidAccepted_doesNotThrow_whenServiceFails() {
        UUID bidId = UUID.randomUUID();
        BidAcceptedEvent event = new BidAcceptedEvent(bidId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        doThrow(new RuntimeException("firestore error")).when(conversationService)
            .createConversationForBid(any(), any(), any());

        listener.handleBidAccepted(event);
        // must not propagate
    }
}
