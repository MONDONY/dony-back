package com.dony.api.messaging;

import com.dony.api.auth.events.UserFinalizedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class UserFinalizedMessagingListener {

    private final FirestoreService firestoreService;

    public UserFinalizedMessagingListener(FirestoreService firestoreService) {
        this.firestoreService = firestoreService;
    }

    @Async
    @EventListener
    public void onUserFinalized(UserFinalizedEvent event) {
        firestoreService.anonymizeUser(event.getUserId().toString());
    }
}
