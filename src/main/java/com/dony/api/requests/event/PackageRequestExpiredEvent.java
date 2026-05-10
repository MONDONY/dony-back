package com.dony.api.requests.event;

import java.util.UUID;

public record PackageRequestExpiredEvent(UUID requestId, UUID senderId) {}
