package com.dony.api.matching;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.dto.AnnouncementDetailResponse;
import com.dony.api.matching.dto.AnnouncementRequest;
import com.dony.api.matching.dto.AnnouncementResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public AnnouncementService(
            AnnouncementRepository announcementRepository,
            BidRepository bidRepository,
            UserRepository userRepository,
            AuditService auditService
    ) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional
    public AnnouncementResponse createAnnouncement(String firebaseUid, AnnouncementRequest request) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));

        if (user.getKycStatus() != KycStatus.VERIFIED) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN,
                    "kyc-not-verified",
                    "KYC Not Verified",
                    "Vérifiez votre identité pour publier un trajet"
            );
        }

        if (!user.getRoles().contains(Role.TRAVELER)) {
            user.getRoles().add(Role.TRAVELER);
            userRepository.save(user);
        }

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(user.getId());
        announcement.setDepartureCity(request.departureCity());
        announcement.setArrivalCity(request.arrivalCity());
        announcement.setDepartureDate(request.departureDate());
        announcement.setAvailableKg(request.availableKg());
        announcement.setPricePerKg(request.pricePerKg());
        announcement.setStatus(AnnouncementStatus.ACTIVE);

        AnnouncementEntity saved = announcementRepository.save(announcement);

        auditService.log(
                "USER",
                user.getId(),
                "ANNOUNCEMENT_CREATED",
                saved.getId(),
                Map.of(
                        "departureCity", saved.getDepartureCity(),
                        "arrivalCity", saved.getArrivalCity(),
                        "departureDate", saved.getDepartureDate().toString(),
                        "availableKg", saved.getAvailableKg().toString(),
                        "pricePerKg", saved.getPricePerKg().toString()
                )
        );

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<AnnouncementResponse> getMyAnnouncements(String firebaseUid, Pageable pageable) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        
        return announcementRepository.findByTravelerId(user.getId(), pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public AnnouncementDetailResponse getAnnouncementDetail(UUID id, String firebaseUid) {
        AnnouncementEntity announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));
        
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));

        if (!announcement.getTravelerId().equals(user.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Vous n'êtes pas autorisé à voir cette annonce");
        }

        long bidsCount = bidRepository.countByAnnouncementId(id);
        
        return new AnnouncementDetailResponse(
                announcement.getId(),
                announcement.getTravelerId(),
                announcement.getDepartureCity(),
                announcement.getArrivalCity(),
                announcement.getDepartureDate(),
                announcement.getAvailableKg(),
                announcement.getPricePerKg(),
                announcement.getStatus().name(),
                bidsCount,
                announcement.getCreatedAt(),
                announcement.getUpdatedAt()
        );
    }

    @Transactional
    public AnnouncementDetailResponse updateAnnouncement(UUID id, String firebaseUid, AnnouncementRequest request) {
        AnnouncementEntity announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));
        
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));

        if (!announcement.getTravelerId().equals(user.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Vous n'êtes pas autorisé à modifier cette annonce");
        }

        boolean hasAcceptedBids = bidRepository.existsByAnnouncementIdAndStatus(id, BidStatus.ACCEPTED);
        if (hasAcceptedBids) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT,
                    "modification-impossible",
                    "Modification Impossible",
                    "Modification impossible : des colis sont déjà acceptés pour ce trajet"
            );
        }

        announcement.setDepartureCity(request.departureCity());
        announcement.setArrivalCity(request.arrivalCity());
        announcement.setDepartureDate(request.departureDate());
        announcement.setAvailableKg(request.availableKg());
        announcement.setPricePerKg(request.pricePerKg());

        AnnouncementEntity saved = announcementRepository.save(announcement);

        auditService.log(
                "USER",
                user.getId(),
                "ANNOUNCEMENT_UPDATED",
                saved.getId(),
                Map.of(
                        "departureCity", saved.getDepartureCity(),
                        "arrivalCity", saved.getArrivalCity(),
                        "departureDate", saved.getDepartureDate().toString(),
                        "availableKg", saved.getAvailableKg().toString(),
                        "pricePerKg", saved.getPricePerKg().toString()
                )
        );

        long bidsCount = bidRepository.countByAnnouncementId(id);

        return new AnnouncementDetailResponse(
                saved.getId(),
                saved.getTravelerId(),
                saved.getDepartureCity(),
                saved.getArrivalCity(),
                saved.getDepartureDate(),
                saved.getAvailableKg(),
                saved.getPricePerKg(),
                saved.getStatus().name(),
                bidsCount,
                saved.getCreatedAt(),
                saved.getUpdatedAt()
        );
    }

    private AnnouncementResponse toResponse(AnnouncementEntity entity) {
        return new AnnouncementResponse(
                entity.getId(),
                entity.getTravelerId(),
                entity.getDepartureCity(),
                entity.getArrivalCity(),
                entity.getDepartureDate(),
                entity.getAvailableKg(),
                entity.getPricePerKg(),
                entity.getStatus().name(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
