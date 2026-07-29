package com.yadony.api.payments.events;

import java.util.UUID;

public record StripeOnboardingCompletedEvent(UUID userId) {}
