package com.dony.api.rebooking;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RebookingService {

    private final TravelerSubscriptionRepository travelerSubscriptionRepository;
    private final UserRepository userRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;

    public RebookingService(TravelerSubscriptionRepository travelerSubscriptionRepository,
                            UserRepository userRepository,
                            BidRepository bidRepository,
                            AnnouncementRepository announcementRepository) {
        this.travelerSubscriptionRepository = travelerSubscriptionRepository;
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
    }

    @Transactional(readOnly = true)
    public List<PastBookingResponse> getPastBookings(String firebaseUid) {
        UserEntity sender = userRepository.findByFirebaseUid(firebaseUid)
            .orElseThrow(() -> new DonyNotFoundException("Sender not found"));

        return bidRepository.findPastBookingsBySender(sender.getId())
            .stream()
            .map(row -> new PastBookingResponse(
                (UUID) row[0],
                (UUID) row[1],
                (String) row[2],
                (String) row[3],
                (String) row[4],   // departureCity
                (String) row[5],   // arrivalCity
                ((java.sql.Date) row[6]).toLocalDate(),
                ((Number) row[7]).longValue()
            ))
            .toList();
    }

    @Transactional
    public RebookResponse rebook(String firebaseUid, UUID pastBidId) {
        UserEntity sender = userRepository.findByFirebaseUid(firebaseUid)
            .orElseThrow(() -> new DonyNotFoundException("Sender not found"));

        BidEntity pastBid = bidRepository.findById(pastBidId)
            .orElseThrow(() -> new DonyNotFoundException("Bid", pastBidId));

        if (!pastBid.getSenderId().equals(sender.getId())) {
            throw new AccessDeniedException("Bid does not belong to sender");
        }

        AnnouncementEntity pastAnn = announcementRepository.findById(pastBid.getAnnouncementId())
            .orElseThrow(() -> new DonyNotFoundException("Announcement", pastBid.getAnnouncementId()));

        Optional<AnnouncementEntity> upcoming = announcementRepository
            .findNextUpcomingByTravelerAndCities(
                pastAnn.getTravelerId(),
                pastAnn.getDepartureCity(),
                pastAnn.getArrivalCity(),
                LocalDate.now()
            );

        if (upcoming.isEmpty()) {
            return new RebookResponse("NO_UPCOMING_TRIP", null);
        }

        BidEntity newBid = new BidEntity();
        newBid.setSenderId(sender.getId());
        newBid.setAnnouncementId(upcoming.get().getId());
        newBid.setWeightKg(pastBid.getWeightKg());
        newBid.setDeclaredValueEur(pastBid.getDeclaredValueEur());
        newBid.setStatus(BidStatus.PENDING);
        BidEntity saved = bidRepository.save(newBid);

        return new RebookResponse("REBOOKED", saved.getId());
    }

    @Transactional
    public void subscribeToTraveler(String firebaseUid, UUID travelerId) {
        UserEntity sender = userRepository.findByFirebaseUid(firebaseUid)
            .orElseThrow(() -> new DonyNotFoundException("Sender not found"));
        userRepository.findById(travelerId)
            .orElseThrow(() -> new DonyNotFoundException("Traveler", travelerId));

        if (travelerSubscriptionRepository.existsBySenderIdAndTravelerId(sender.getId(), travelerId)) {
            return;
        }

        TravelerSubscriptionEntity sub = new TravelerSubscriptionEntity();
        sub.setSenderId(sender.getId());
        sub.setTravelerId(travelerId);
        travelerSubscriptionRepository.save(sub);
    }
}
