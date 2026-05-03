package com.dony.api.matching;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.dto.BidRejectRequest;
import com.dony.api.matching.dto.BidRequest;
import com.dony.api.matching.dto.BidResponse;
import com.dony.api.matching.dto.HandoverRequest;
import com.dony.api.matching.dto.RefuseParcelRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class BidController {

    private final BidService bidService;

    public BidController(BidService bidService) {
        this.bidService = bidService;
    }

    // ── Sender creates a bid on an announcement ───────────────────────────────

    @PostMapping("/announcements/{announcementId}/bids")
    public ResponseEntity<BidResponse> createBid(
            @PathVariable UUID announcementId,
            @Valid @RequestBody BidRequest request,
            HttpServletRequest httpRequest
    ) {
        String firebaseUid = requireFirebaseUid();
        BidResponse response = bidService.createBid(announcementId, firebaseUid, request, httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ── Traveler views bids on their announcement ─────────────────────────────

    @GetMapping("/announcements/{announcementId}/bids")
    public ResponseEntity<List<BidResponse>> getBidsForAnnouncement(@PathVariable UUID announcementId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.getBidsForAnnouncement(announcementId, firebaseUid));
    }

    // ── Bid detail (traveler or sender) ──────────────────────────────────────

    @GetMapping("/bids/{bidId}")
    public ResponseEntity<BidResponse> getBidById(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.getBidById(bidId, firebaseUid));
    }

    @GetMapping("/bids/me")
    public ResponseEntity<List<BidResponse>> getMyBids() {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.getMyBids(firebaseUid));
    }

    // ── Traveler accepts a bid ────────────────────────────────────────────────

    @PutMapping("/bids/{bidId}/accept")
    public ResponseEntity<BidResponse> acceptBid(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.acceptBid(bidId, firebaseUid));
    }

    // ── Traveler rejects a bid ────────────────────────────────────────────────

    @PutMapping("/bids/{bidId}/reject")
    public ResponseEntity<BidResponse> rejectBid(
            @PathVariable UUID bidId,
            @Valid @RequestBody(required = false) BidRejectRequest request
    ) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.rejectBid(bidId, firebaseUid, request));
    }

    // ── Traveler defines handover window ─────────────────────────────────────

    @PutMapping("/bids/{bidId}/handover")
    public ResponseEntity<BidResponse> setHandover(
            @PathVariable UUID bidId,
            @Valid @RequestBody HandoverRequest request
    ) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.setHandover(bidId, firebaseUid, request));
    }

    // ── Traveler confirms presence ────────────────────────────────────────────

    @PutMapping("/bids/{bidId}/confirm-presence")
    public ResponseEntity<BidResponse> confirmPresence(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.confirmPresence(bidId, firebaseUid));
    }

    @PutMapping("/bids/{bidId}/cancel")
    public ResponseEntity<BidResponse> cancelBid(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.cancelBid(bidId, firebaseUid));
    }

    // Story 9.4 — Voyageur refuse le colis lors de l'inspection
    @PostMapping("/bids/{bidId}/refuse-parcel")
    @PreAuthorize("hasRole('TRAVELER')")
    public ResponseEntity<BidResponse> refuseParcel(@PathVariable UUID bidId,
                                                     @Valid @RequestBody RefuseParcelRequest request) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(bidService.refuseParcel(bidId, firebaseUid,
                request.reason(), request.refusalPhotoUrl()));
    }

    @DeleteMapping("/bids/{bidId}/me")
    public ResponseEntity<Void> hideBid(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        bidService.hideBidForSender(bidId, firebaseUid);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/bids/{bidId}/traveler")
    public ResponseEntity<Void> dismissBidAsTraveler(@PathVariable UUID bidId) {
        String firebaseUid = requireFirebaseUid();
        bidService.hideBidForTraveler(bidId, firebaseUid);
        return ResponseEntity.noContent().build();
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new DonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Un token Firebase valide est requis");
        }
        return (String) auth.getPrincipal();
    }
}
