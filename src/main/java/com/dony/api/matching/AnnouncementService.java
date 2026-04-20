package com.dony.api.matching;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.dto.AnnouncementRequest;
import com.dony.api.matching.dto.AnnouncementResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public AnnouncementService(
            AnnouncementRepository announcementRepository,
            UserRepository userRepository,
            AuditService auditService
    ) {
        this.announcementRepository = announcementRepository;
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
