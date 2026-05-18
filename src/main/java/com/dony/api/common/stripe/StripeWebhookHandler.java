package com.dony.api.common.stripe;

import com.stripe.model.Event;

public interface StripeWebhookHandler {
    boolean supports(String eventType);
    void handle(Event event);
}
