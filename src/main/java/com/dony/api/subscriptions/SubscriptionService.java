package com.dony.api.subscriptions;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.subscriptions.dto.SubscriptionItemResponse;
import com.dony.api.subscriptions.dto.SubscriptionStatusResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class SubscriptionService {

    private final TravelerSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    public SubscriptionService(TravelerSubscriptionRepository subscriptionRepository,
                               UserRepository userRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
    }

    private UUID senderId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
            .orElseThrow(() -> new DonyNotFoundException("Sender not found"))
            .getId();
    }

    @Transactional
    public void subscribe(String firebaseUid, UUID travelerId) {
        UUID sid = senderId(firebaseUid);
        userRepository.findById(travelerId)
            .orElseThrow(() -> new DonyNotFoundException("Traveler", travelerId));

        var existing = subscriptionRepository.findBySenderIdAndTravelerIdIncludingDeleted(sid, travelerId);
        if (existing.isPresent()) {
            TravelerSubscriptionEntity sub = existing.get();
            if (sub.getDeletedAt() != null) {   // réactiver un abonnement soft-deleted
                sub.setDeletedAt(null);
                sub.setHasNew(false);           // indicateur "nouveau" obsolète après désabonnement
                subscriptionRepository.save(sub);
            }
            return;
        }
        TravelerSubscriptionEntity sub = new TravelerSubscriptionEntity();
        sub.setSenderId(sid);
        sub.setTravelerId(travelerId);
        try {
            subscriptionRepository.save(sub);
        } catch (DataIntegrityViolationException e) {
            // Double-tap concurrent : la contrainte UNIQUE(sender_id, traveler_id) a déjà
            // créé la ligne — abonnement idempotent, on ignore.
        }
    }

    @Transactional
    public void unsubscribe(String firebaseUid, UUID travelerId) {
        UUID sid = senderId(firebaseUid);
        subscriptionRepository.findBySenderIdAndTravelerId(sid, travelerId).ifPresent(sub -> {
            sub.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
            subscriptionRepository.save(sub);
        });
    }

    @Transactional
    public void setPush(String firebaseUid, UUID travelerId, boolean enabled) {
        UUID sid = senderId(firebaseUid);
        TravelerSubscriptionEntity sub = subscriptionRepository.findBySenderIdAndTravelerId(sid, travelerId)
            .orElseThrow(() -> new DonyNotFoundException("Subscription not found"));
        sub.setPushEnabled(enabled);
        subscriptionRepository.save(sub);
    }

    @Transactional
    public void markSeen(String firebaseUid, UUID travelerId) {
        UUID sid = senderId(firebaseUid);
        subscriptionRepository.findBySenderIdAndTravelerId(sid, travelerId).ifPresent(sub -> {
            sub.setHasNew(false);
            subscriptionRepository.save(sub);
        });
    }

    @Transactional(readOnly = true)
    public SubscriptionStatusResponse getStatus(String firebaseUid, UUID travelerId) {
        UUID sid = senderId(firebaseUid);
        return subscriptionRepository.findBySenderIdAndTravelerId(sid, travelerId)
            .map(s -> new SubscriptionStatusResponse(true, s.isPushEnabled()))
            .orElse(new SubscriptionStatusResponse(false, false));
    }

    @Transactional(readOnly = true)
    public List<SubscriptionItemResponse> getMySubscriptions(String firebaseUid) {
        UUID sid = senderId(firebaseUid);
        return subscriptionRepository.findEnrichedBySenderId(sid).stream()
            .map(this::mapRow)
            .toList();
    }

    private SubscriptionItemResponse mapRow(Object[] r) {
        SubscriptionItemResponse.LastAnnouncement last = null;
        if (r[7] != null) {
            last = new SubscriptionItemResponse.LastAnnouncement(
                (UUID) r[7], (String) r[8], (String) r[9],
                (BigDecimal) r[10],
                ((java.sql.Timestamp) r[11]).toLocalDateTime()
            );
        }
        return new SubscriptionItemResponse(
            (UUID) r[0],
            (String) r[1],
            (Boolean) r[2],
            (BigDecimal) r[3],
            ((Number) r[4]).longValue(),
            (Boolean) r[5],
            (Boolean) r[6],
            last
        );
    }
}
