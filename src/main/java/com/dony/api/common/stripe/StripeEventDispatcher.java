package com.dony.api.common.stripe;

import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StripeEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(StripeEventDispatcher.class);

    private final List<StripeWebhookHandler> handlers;

    public StripeEventDispatcher(List<StripeWebhookHandler> handlers) {
        this.handlers = handlers;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean dispatch(String payload) {
        Event event = ApiResource.GSON.fromJson(payload, Event.class);
        String type = event.getType();

        for (StripeWebhookHandler handler : handlers) {
            if (handler.supports(type)) {
                log.info("Dispatching event {} ({}) to {}", event.getId(), type,
                        handler.getClass().getSimpleName());
                handler.handle(event);
                return true;
            }
        }
        log.debug("No handler for event type {} — marking SKIPPED", type);
        return false;
    }
}
