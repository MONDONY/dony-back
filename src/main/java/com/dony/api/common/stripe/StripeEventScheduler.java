package com.dony.api.common.stripe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dony.stripe.webhook.scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class StripeEventScheduler {

    private static final Logger log = LoggerFactory.getLogger(StripeEventScheduler.class);

    private final StripeEventProcessor processor;
    private final StripeWebhookProperties props;

    public StripeEventScheduler(StripeEventProcessor processor, StripeWebhookProperties props) {
        this.processor = processor;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${dony.stripe.webhook.poll-interval:PT10S}")
    public void poll() {
        int processed = 0;
        while (processed < props.batchSize() && processor.processOne()) {
            processed++;
        }
        if (processed > 0) log.info("Stripe event scheduler processed {} events", processed);
    }
}
