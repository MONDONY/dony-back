package com.dony.api.matching;

import com.dony.api.matching.events.BidExpiredOnDepartureEvent;
import com.dony.api.matching.events.BidRejectedEvent;
import com.dony.api.matching.events.ParcelRefusedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BidPhotoLifecycleListenerTest {

    @Mock private BidPhotoService bidPhotoService;
    @InjectMocks private BidPhotoLifecycleListener listener;

    @Test
    void onRejected_marksDeleting() {
        UUID bid = UUID.randomUUID();
        listener.onRejected(new BidRejectedEvent(bid, UUID.randomUUID(), "reason"));
        verify(bidPhotoService).markDeletingForBid(bid);
    }

    @Test
    void onParcelRefused_marksDeleting() {
        UUID bid = UUID.randomUUID();
        listener.onParcelRefused(new ParcelRefusedEvent(bid, UUID.randomUUID(), UUID.randomUUID(), "r"));
        verify(bidPhotoService).markDeletingForBid(bid);
    }

    @Test
    void onExpired_marksDeleting() {
        UUID bid = UUID.randomUUID();
        listener.onExpired(new BidExpiredOnDepartureEvent(
                bid, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        verify(bidPhotoService).markDeletingForBid(bid);
    }
}
