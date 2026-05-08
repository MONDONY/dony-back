package com.dony.api.messaging;

import com.dony.api.auth.events.UserFinalizedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class UserFinalizedMessagingListener {

    private final FirestoreService firestoreService;

    public UserFinalizedMessagingListener(FirestoreService firestoreService) {
        this.firestoreService = firestoreService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserFinalized(UserFinalizedEvent event) {
        firestoreService.anonymizeUser(event.getUserId().toString());
    }
}
