package com.dony.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class AccountDeletionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionScheduler.class);

    private final UserRepository userRepository;
    private final UserService userService;

    public AccountDeletionScheduler(UserRepository userRepository, UserService userService) {
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void finalizeExpiredDeletions() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        List<UserEntity> toDelete = userRepository
                .findByStatusAndDeletionRequestedAtBefore(UserStatus.PENDING_DELETION, cutoff);

        log.info("Account deletion scheduler: {} account(s) to finalize", toDelete.size());
        toDelete.forEach(userService::finalizeGdprDeletion);
    }
}
