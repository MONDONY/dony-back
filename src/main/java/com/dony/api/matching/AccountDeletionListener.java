package com.dony.api.matching;

import com.dony.api.auth.events.AccountDeletionRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AccountDeletionListener {

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;

    public AccountDeletionListener(AnnouncementRepository announcementRepository,
                                   BidRepository bidRepository) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
    }

    @EventListener
    @Transactional
    public void onDeletionRequested(AccountDeletionRequestedEvent event) {
        announcementRepository.cancelOpenAnnouncementsByUserId(event.getUserId());
        bidRepository.cancelOpenSenderBidsByUserId(event.getUserId());
        bidRepository.cancelOpenTravelerBidsByUserId(event.getUserId());
    }
}
