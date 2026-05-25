package com.dony.api.rebooking;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class RebookingController {

    private final RebookingService rebookingService;

    public RebookingController(RebookingService rebookingService) {
        this.rebookingService = rebookingService;
    }

    @GetMapping("/senders/me/past-bookings")
    @PreAuthorize("hasRole('SENDER')")
    public List<PastBookingResponse> pastBookings(
            @AuthenticationPrincipal String firebaseUid) {
        return rebookingService.getPastBookings(firebaseUid);
    }

    @PostMapping("/bookings/rebook/{pastBidId}")
    @PreAuthorize("hasRole('SENDER')")
    public RebookResponse rebook(
            @AuthenticationPrincipal String firebaseUid,
            @PathVariable UUID pastBidId) {
        return rebookingService.rebook(firebaseUid, pastBidId);
    }

    @PostMapping("/travelers/{travelerId}/notify-when-available")
    @PreAuthorize("hasRole('SENDER')")
    @ResponseStatus(HttpStatus.CREATED)
    public void notifyWhenAvailable(
            @AuthenticationPrincipal String firebaseUid,
            @PathVariable UUID travelerId) {
        rebookingService.subscribeToTraveler(firebaseUid, travelerId);
    }
}
