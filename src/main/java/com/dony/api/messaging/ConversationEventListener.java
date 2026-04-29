package com.dony.api.messaging;

import com.dony.api.matching.events.BidAcceptedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class ConversationEventListener {

    private static final Logger log = LoggerFactory.getLogger(ConversationEventListener.class);
    private final ConversationService conversationService;

    public ConversationEventListener(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @EventListener
    @Async
    public void handleBidAccepted(BidAcceptedEvent event) {
        try {
            conversationService.createConversationForBid(
                event.getBidId(), event.getSenderId(), event.getTravelerId()
            );
        } catch (Exception e) {
            log.error("Failed to create conversation for bid {}: {}", event.getBidId(), e.getMessage());
        }
    }
}
