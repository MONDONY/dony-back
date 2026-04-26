package com.dony.api.tracking;

import com.dony.api.tracking.dto.QrCodeResponse;
import com.dony.api.tracking.dto.TrackingSearchResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/tracking")
public class TrackingController {

    private final TrackingService trackingService;

    public TrackingController(TrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @GetMapping("/{bidId}/qr-code")
    public ResponseEntity<QrCodeResponse> getQrCode(
            @PathVariable UUID bidId,
            @AuthenticationPrincipal String firebaseUid) {
        return ResponseEntity.ok(trackingService.getQrCode(bidId, firebaseUid));
    }

    @GetMapping("/search")
    public ResponseEntity<TrackingSearchResponse> search(@RequestParam String number) {
        return ResponseEntity.ok(trackingService.searchByTrackingNumber(number));
    }
}
