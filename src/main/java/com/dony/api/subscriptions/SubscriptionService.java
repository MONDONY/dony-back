package com.dony.api.subscriptions;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public void subscribe(String firebaseUid, UUID travelerId) {
        UserEntity sender = userRepository.findByFirebaseUid(firebaseUid)
            .orElseThrow(() -> new DonyNotFoundException("Sender not found"));
        userRepository.findById(travelerId)
            .orElseThrow(() -> new DonyNotFoundException("Traveler", travelerId));
        if (subscriptionRepository.existsBySenderIdAndTravelerId(sender.getId(), travelerId)) {
            return;
        }
        TravelerSubscriptionEntity sub = new TravelerSubscriptionEntity();
        sub.setSenderId(sender.getId());
        sub.setTravelerId(travelerId);
        subscriptionRepository.save(sub);
    }
}
