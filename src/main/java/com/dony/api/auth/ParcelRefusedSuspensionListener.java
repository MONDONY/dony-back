package com.dony.api.auth;

import com.dony.api.matching.events.ParcelRefusedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

// Story 9.5 — Trigger suspension check after parcel refusal
@Component
public class ParcelRefusedSuspensionListener {

    private final UserService userService;

    public ParcelRefusedSuspensionListener(UserService userService) {
        this.userService = userService;
    }

    @EventListener
    @Async
    public void onParcelRefused(ParcelRefusedEvent event) {
        userService.checkAndSuspendSender(event.getSenderId());
    }
}
