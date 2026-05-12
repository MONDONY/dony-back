package com.dony.api.referral;

import com.dony.api.auth.events.UserRegisteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for {@link UserRegisteredEvent} and generates a referral code for the new user.
 * Uses a simple synchronous {@code @EventListener} because code generation is fast
 * and must complete within the same transaction as user creation.
 */
@Component
public class UserRegisteredReferralListener {

    private static final Logger log = LoggerFactory.getLogger(UserRegisteredReferralListener.class);

    private final ReferralService referralService;

    public UserRegisteredReferralListener(ReferralService referralService) {
        this.referralService = referralService;
    }

    @EventListener
    public void onUserRegistered(UserRegisteredEvent event) {
        try {
            referralService.generateCodeForUser(event.userId());
            log.debug("Referral code generated for user {}", event.userId());
        } catch (Exception e) {
            // Non-blocking: code generation failure must not break registration
            log.error("Failed to generate referral code for user {}: {}", event.userId(), e.getMessage(), e);
        }
    }
}
