package com.yadony.api.auth;

import java.util.UUID;

public record UserProStatusChangedEvent(UUID userId, boolean isPro) {}
