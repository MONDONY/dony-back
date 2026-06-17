package com.dony.api.matching;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.StorageService;
import com.dony.api.matching.dto.BidPhotoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Cycle de vie des photos de colis : upload, attache (ACTIVE), lecture présignée, passage DELETING. */
@Service
public class BidPhotoService {

    static final int MAX_PHOTOS = 4;
    static final String PHOTO_PREFIX = "bids/";
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    private final BidPhotoRepository photoRepository;
    private final StorageService storageService;

    public BidPhotoService(BidPhotoRepository photoRepository, StorageService storageService) {
        this.photoRepository = photoRepository;
        this.storageService = storageService;
    }

    /** Upload une photo sous le prefix bids/{senderId}/ ; renvoie la clé S3. */
    public String uploadPhoto(UUID senderId, MultipartFile file) {
        try {
            return storageService.uploadFile(file, PHOTO_PREFIX + senderId + "/");
        } catch (IOException e) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "photo-upload-failed", "Photo Upload Failed",
                    "Impossible d'enregistrer la photo");
        }
    }

    /** Persiste jusqu'à MAX_PHOTOS lignes ACTIVE pour un bid fraîchement créé. */
    @Transactional
    public void attachPhotos(UUID bidId, List<String> photoKeys) {
        if (photoKeys == null || photoKeys.isEmpty()) {
            return;
        }
        if (photoKeys.size() > MAX_PHOTOS) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "too-many-photos", "Too Many Photos",
                    "Maximum " + MAX_PHOTOS + " photos par colis");
        }
        List<BidPhotoEntity> rows = new ArrayList<>();
        int position = 0;
        for (String key : photoKeys) {
            if (key == null || !key.startsWith(PHOTO_PREFIX)) {
                throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid-photo-key", "Invalid Photo Key",
                        "Clé photo invalide");
            }
            rows.add(new BidPhotoEntity(bidId, key, position++));
        }
        photoRepository.saveAll(rows);
    }

    /** Photos ACTIVE en URLs présignées, triées par position. */
    public List<BidPhotoResponse> activePhotos(UUID bidId) {
        return photoRepository
                .findByBidIdAndStatusOrderByPositionAsc(bidId, BidPhotoStatus.ACTIVE)
                .stream()
                .map(p -> new BidPhotoResponse(p.getId(),
                        storageService.generatePresignedUrl(p.getObjectKey(), PRESIGN_TTL)))
                .toList();
    }

    /** Passe toutes les photos ACTIVE d'un bid en DELETING (idempotent). */
    @Transactional
    public void markDeletingForBid(UUID bidId) {
        List<BidPhotoEntity> active =
                photoRepository.findByBidIdAndStatusOrderByPositionAsc(bidId, BidPhotoStatus.ACTIVE);
        for (BidPhotoEntity p : active) {
            p.markDeleting();
        }
        photoRepository.saveAll(active);
    }
}
