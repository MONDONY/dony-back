package com.dony.api.payments;

import com.dony.api.auth.UserEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StripeCustomerService {

    private static final Logger log = LoggerFactory.getLogger(StripeCustomerService.class);

    public void cleanupForUser(UserEntity user) {
        // Stripe Connect account (stripeAccountId) : not closed here — Stripe requires
        // a manual process with pending balance verification.
        // Stripe Customer ID : not yet stored on UserEntity in this MVP.
        // Access is blocked because Firebase user is deleted.
        log.info("Stripe cleanup skipped for user {} (MVP — no stripeCustomerId field)", user.getId());
    }
}
