package com.yadony.api.requests.event;

import java.util.UUID;

public record NegotiationExpiredEvent(UUID threadId, UUID packageRequestId, UUID senderId, UUID travelerId) {}
