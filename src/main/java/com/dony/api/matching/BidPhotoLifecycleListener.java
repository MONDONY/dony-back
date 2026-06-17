package com.dony.api.matching;

import com.dony.api.matching.events.BidExpiredOnDepartureEvent;
import com.dony.api.matching.events.BidRejectedEvent;
import com.dony.api.matching.events.ParcelRefusedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marque les photos d'un bid en DELETING dès qu'il atteint un état terminal.
 * BidRejectedEvent couvre REJECTED et CANCELLED (cancelBid republie cet event).
 * IN_TRANSIT / NO_SHOW n'ont pas d'event dédié ici → rattrapés par le balayage
 * défensif du BidPhotoCleanupScheduler.
 */
@Component
public class BidPhotoLifecycleListener {

    private final BidPhotoService bidPhotoService;

    public BidPhotoLifecycleListener(BidPhotoService bidPhotoService) {
        this.bidPhotoService = bidPhotoService;
    }

    @EventListener
    @Transactional
    public void onRejected(BidRejectedEvent event) {
        bidPhotoService.markDeletingForBid(event.getBidId());
    }

    @EventListener
    @Transactional
    public void onParcelRefused(ParcelRefusedEvent event) {
        bidPhotoService.markDeletingForBid(event.getBidId());
    }

    @EventListener
    @Transactional
    public void onExpired(BidExpiredOnDepartureEvent event) {
        bidPhotoService.markDeletingForBid(event.getBidId());
    }
}
