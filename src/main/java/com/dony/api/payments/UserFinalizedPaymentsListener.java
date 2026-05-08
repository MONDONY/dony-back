package com.dony.api.payments;

import com.dony.api.auth.UserRepository;
import com.dony.api.auth.events.UserFinalizedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class UserFinalizedPaymentsListener {

    private final StripeCustomerService stripeCustomerService;
    private final UserRepository userRepository;

    public UserFinalizedPaymentsListener(StripeCustomerService stripeCustomerService,
                                         UserRepository userRepository) {
        this.stripeCustomerService = stripeCustomerService;
        this.userRepository = userRepository;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserFinalized(UserFinalizedEvent event) {
        userRepository.findById(event.getUserId())
                .ifPresent(stripeCustomerService::cleanupForUser);
    }
}
