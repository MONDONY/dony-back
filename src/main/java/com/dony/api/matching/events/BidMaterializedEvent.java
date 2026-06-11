package com.dony.api.matching.events;

import java.util.UUID;

/**
 * Published by matching/ (ThreadAcceptedBidListener) once the bid backing an
 * accepted NegotiationThread has been materialised/saved. Consumed by requests/
 * (BidMaterializedListener) to stamp {@code materialized_bid_id} on the thread,
 * so the mobile app can open the bid detail (tracking, no-show…) from the thread.
 *
 * Cross-package contract: matching/ publishes, requests/ listens — no direct
 * service injection between the two packages.
 */
public class BidMaterializedEvent {
    private final UUID negotiationThreadId;
    private final UUID bidId;

    public BidMaterializedEvent(UUID negotiationThreadId, UUID bidId) {
        this.negotiationThreadId = negotiationThreadId;
        this.bidId = bidId;
    }

    public UUID getNegotiationThreadId() { return negotiationThreadId; }
    public UUID getBidId() { return bidId; }
}
