package com.dony.api.auth;

import java.util.UUID;

public record UserProStatusChangedEvent(UUID userId, boolean isPro) {}
