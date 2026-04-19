package com.dony.api.common;

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

@RestController
@RequestMapping("/storage")
public class StorageController {

    private final StorageService storageService;

    public StorageController(StorageService storageService) {
        this.storageService = storageService;
    }

    /**
     * Upload a QR delivery photo.
     * Prefix built server-side: tracking/{bidId}/
     */
    @PostMapping("/upload/tracking")
    public ResponseEntity<Map<String, String>> uploadTrackingPhoto(
            @RequestParam("file") MultipartFile file,
            @RequestParam("bidId") String bidId) throws IOException {

        String prefix = "tracking/" + bidId + "/";
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

        String prefix = "users/" + uid + "/";
        String key = storageService.uploadFile(file, prefix);
        String url = storageService.generatePresignedUrl(key, Duration.ofHours(1));

        return ResponseEntity.ok(Map.of("key", key, "url", url));
    }
}
