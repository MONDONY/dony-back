package com.dony.api.subscriptions;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping("/travelers/{travelerId}/subscribe")
    @PreAuthorize("hasRole('SENDER')")
    @ResponseStatus(HttpStatus.CREATED)
    public void subscribe(@AuthenticationPrincipal String firebaseUid,
                          @PathVariable UUID travelerId) {
        subscriptionService.subscribe(firebaseUid, travelerId);
    }
}
