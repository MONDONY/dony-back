package com.dony.api.matching;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.PageResponse;
import com.dony.api.matching.dto.AnnouncementDetailResponse;
import com.dony.api.matching.dto.AnnouncementRequest;
import com.dony.api.matching.dto.AnnouncementResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/announcements")
public class AnnouncementController {

    private final AnnouncementService announcementService;

    public AnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @PostMapping
    public ResponseEntity<AnnouncementResponse> createAnnouncement(@Valid @RequestBody AnnouncementRequest request) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(announcementService.createAnnouncement(firebaseUid, request));
    }

    @GetMapping("/my")
    public ResponseEntity<PageResponse<AnnouncementResponse>> getMyAnnouncements(Pageable pageable) {
        String firebaseUid = requireFirebaseUid();
        Page<AnnouncementResponse> page = announcementService.getMyAnnouncements(firebaseUid, pageable);
        return ResponseEntity.ok(PageResponse.from(page));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AnnouncementDetailResponse> getAnnouncementDetail(@PathVariable UUID id) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(announcementService.getAnnouncementDetail(id, firebaseUid));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AnnouncementDetailResponse> updateAnnouncement(
            @PathVariable UUID id,
            @Valid @RequestBody AnnouncementRequest request
    ) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(announcementService.updateAnnouncement(id, firebaseUid, request));
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new DonyBusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "unauthorized",
                    "Unauthorized",
                    "Un token Firebase valide est requis"
            );
        }
        return (String) auth.getPrincipal();
    }
}
