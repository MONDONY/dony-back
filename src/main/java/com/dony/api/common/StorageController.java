package com.dony.api.common;

import com.dony.api.auth.UserRepository;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/storage")
public class StorageController {

    private static final Pattern UID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final StorageService storageService;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;

    public StorageController(StorageService storageService,
                             BidRepository bidRepository,
                             AnnouncementRepository announcementRepository,
                             UserRepository userRepository) {
        this.storageService = storageService;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
    }

    /**
     * Upload a QR delivery photo.
     * Prefix built server-side: tracking/{bidId}/
     */
    @PostMapping("/upload/tracking")
    public ResponseEntity<Map<String, String>> uploadTrackingPhoto(
            @RequestParam("file") MultipartFile file,
            @RequestParam("bidId") String bidId,
            @AuthenticationPrincipal String uid) throws IOException {

        UUID bidUuid;
        try {
            bidUuid = UUID.fromString(bidId);
        } catch (IllegalArgumentException ex) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid-bid-id", "Invalid Bid Id", "bidId doit être un UUID");
        }

        var bid = bidRepository.findById(bidUuid).orElseThrow(() -> new DonyBusinessException(
                HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
        var announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Not Found", "Annonce introuvable"));
        var user = userRepository.findByFirebaseUid(uid).orElseThrow(() -> new DonyBusinessException(
                HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthorized", "Utilisateur introuvable"));
        if (!announcement.getTravelerId().equals(user.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN,
                    "not-bid-traveler", "Forbidden",
                    "Seul le voyageur de l'annonce peut uploader une photo de suivi");
        }

        String prefix = "tracking/" + bidUuid + "/";
        String key = storageService.uploadFile(file, prefix);
        String url = storageService.generatePresignedUrl(key, Duration.ofHours(1));

        return ResponseEntity.ok(Map.of("key", key, "url", url));
    }

    /**
     * Upload a user profile picture.
     * Prefix built server-side: users/{uid}/
     */
    @PostMapping("/upload/profile")
    public ResponseEntity<Map<String, String>> uploadProfilePicture(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal String uid) throws IOException {

        if (uid == null || !UID_PATTERN.matcher(uid).matches()) {
            throw new DonyBusinessException(HttpStatus.UNAUTHORIZED,
                    "invalid-uid", "Unauthorized", "Identifiant utilisateur invalide");
        }

        String prefix = "users/" + uid + "/";
        String key = storageService.uploadFile(file, prefix);
        String url = storageService.generatePresignedUrl(key, Duration.ofHours(1));

        return ResponseEntity.ok(Map.of("key", key, "url", url));
    }

    /**
     * Upload a package request photo (sender side).
     * Prefix built server-side: package_requests/{userId}/{timestamp}_photo.jpg
     * Returns a presigned URL valid 7 days for embedding in the request.
     */
    @PostMapping("/upload/package-request")
    public ResponseEntity<Map<String, String>> uploadPackageRequestPhoto(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal String uid) throws IOException {

        // Le prefix utilise user.getId() (UUID DB), jamais le raw uid → pas de risque
        // d'injection de chemin. On résout l'utilisateur comme le reste de l'app
        // (findByFirebaseUid) sans imposer UID_PATTERN, qui rejetait à tort certains
        // UID Firebase valides (ex. environnements dev / UID non strictement alphanum).
        if (uid == null) {
            throw new DonyBusinessException(HttpStatus.UNAUTHORIZED,
                    "unauthenticated", "Unauthorized", "Non authentifié");
        }
        var user = userRepository.findByFirebaseUid(uid).orElseThrow(() -> new DonyBusinessException(
                HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthorized", "Utilisateur introuvable"));

        String prefix = "package_requests/" + user.getId() + "/";
        String key = storageService.uploadFile(file, prefix);
        // Public URL for marketplace display (signed 7 days, refresh on detail fetch).
        String url = storageService.generatePresignedUrl(key, Duration.ofDays(7));

        return ResponseEntity.ok(Map.of("key", key, "url", url));
    }
}
