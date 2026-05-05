package com.dony.api.payments.events;

import java.util.UUID;

public record StripeOnboardingCompletedEvent(UUID userId) {}
