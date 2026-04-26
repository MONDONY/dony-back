package com.dony.api.tracking;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.notifications.NotificationDispatcher;
import com.dony.api.payments.PaymentEntity;
import com.dony.api.payments.PaymentRepository;
import com.dony.api.payments.PaymentStatus;
import com.dony.api.tracking.dto.ConfirmCodeResponse;
import com.dony.api.tracking.dto.ConfirmDeliveryRequest;
import com.dony.api.tracking.dto.QrCodeResponse;
import com.dony.api.tracking.dto.QrScanRequest;
import com.dony.api.tracking.dto.TrackingEventResponse;
import com.dony.api.tracking.dto.TrackingSearchResponse;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class TrackingService {

    private final BidRepository bidRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;
    private final TrackingEventRepository trackingEventRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.dony.api.common.StorageService storageService;
    private final NotificationDispatcher notificationDispatcher;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_CODE_ATTEMPTS = 3;

    @Value("${app.base-url}")
    private String appBaseUrl;

    public TrackingService(BidRepository bidRepository,
                           PaymentRepository paymentRepository,
                           UserRepository userRepository,
                           AnnouncementRepository announcementRepository,
                           TrackingEventRepository trackingEventRepository,
                           AuditService auditService,
                           ApplicationEventPublisher eventPublisher,
                           com.dony.api.common.StorageService storageService,
                           NotificationDispatcher notificationDispatcher) {
        this.bidRepository = bidRepository;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.announcementRepository = announcementRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.storageService = storageService;
        this.notificationDispatcher = notificationDispatcher;
    }

    public QrCodeResponse getQrCode(UUID bidId, String firebaseUid) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                        "Transaction introuvable"));

        UserEntity currentUser = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found",
                        "Utilisateur introuvable"));

        if (!currentUser.getId().equals(bid.getSenderId())) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Accès interdit à ce QR code");
        }

        if (bid.getQrToken() == null) {
            throw new DonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "qr-not-ready", "QR Not Ready",
                    "Le QR code n'est pas encore disponible pour cette transaction");
        }

        String scanUrl = appBaseUrl + "/api/v1/tracking/" + bidId + "/scan";
        String qrBase64 = generateQrBase64(scanUrl);

        return new QrCodeResponse(bidId, scanUrl, qrBase64);
    }

    public TrackingSearchResponse searchByTrackingNumber(String trackingNumber) {
        String normalized = trackingNumber.trim().toUpperCase();
        BidEntity bid = bidRepository.findByTrackingNumber(normalized)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "tracking-not-found", "Tracking Not Found",
                        "Aucun colis trouvé avec le numéro : " + normalized));

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found",
                        "Annonce introuvable"));

        java.util.Optional<PaymentEntity> paymentOpt = paymentRepository.findByBidId(bid.getId());

        String currentStep;
        String stepLabel;

        if (bid.getStatus() == BidStatus.REJECTED) {
            currentStep = "REJECTED";
            stepLabel = "Refusé";
        } else if (bid.getStatus() == BidStatus.CANCELLED) {
            currentStep = "CANCELLED";
            stepLabel = "Annulé";
        } else if (bid.getStatus() == BidStatus.PENDING) {
            currentStep = "PENDING";
            stepLabel = "En attente de confirmation";
        } else {
            // ACCEPTED — calcul de base depuis paiement/confirmation
            if (paymentOpt.isEmpty() || paymentOpt.get().getStatus() == PaymentStatus.PENDING) {
                currentStep = "ACCEPTED";
                stepLabel = "Voyage confirmé — paiement en attente";
            } else if (paymentOpt.get().getStatus() == PaymentStatus.ESCROW && !bid.isVoyageurConfirmed()) {
                currentStep = "PAYMENT_SECURED";
                stepLabel = "Paiement sécurisé — remise prévue";
            } else if (paymentOpt.get().getStatus() == PaymentStatus.ESCROW && bid.isVoyageurConfirmed()) {
                currentStep = "IN_TRANSIT";
                stepLabel = "En transit";
            } else {
                currentStep = "DELIVERED";
                stepLabel = "Livré";
            }

            // Priorité aux scans réels — ils reflètent l'état physique du colis
            List<TrackingEventEntity> events =
                    trackingEventRepository.findByBidIdOrderByScannedAtAsc(bid.getId());
            boolean hasArrivee = events.stream()
                    .anyMatch(e -> e.getEventType() == TrackingEventType.ARRIVEE);
            boolean hasTransit = events.stream()
                    .anyMatch(e -> e.getEventType() == TrackingEventType.TRANSIT);
            boolean hasDepart = events.stream()
                    .anyMatch(e -> e.getEventType() == TrackingEventType.DEPART);

            if (hasArrivee) {
                currentStep = "DELIVERED";
                stepLabel = "Livraison confirmée ✓";
            } else if (hasTransit) {
                currentStep = "IN_TRANSIT";
                stepLabel = "En transit";
            } else if (hasDepart) {
                currentStep = "DEPARTED";
                stepLabel = "Colis remis au voyageur — en route";
            }
        }

        String paymentStatus = paymentOpt.map(p -> p.getStatus().name()).orElse("NONE");

        return new TrackingSearchResponse(
                bid.getTrackingNumber(),
                bid.getId(),
                announcement.getDepartureCity(),
                announcement.getArrivalCity(),
                currentStep,
                stepLabel,
                paymentStatus
        );
    }

    @Transactional
    public TrackingEventResponse processScan(QrScanRequest request, String firebaseUid) {
        BidEntity bid = bidRepository.findById(request.bidId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                        "Transaction introuvable"));

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found",
                        "Annonce introuvable"));

        UserEntity traveler = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found",
                        "Utilisateur introuvable"));

        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul le voyageur de cette annonce peut scanner le QR code");
        }

        if (bid.getStatus() != BidStatus.ACCEPTED) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "bid-not-accepted",
                    "Bid Not Accepted", "Ce colis n'est pas dans un état scannable");
        }

        if (bid.getQrToken() == null) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "qr-not-ready",
                    "QR Not Ready", "Le QR code n'est pas encore disponible");
        }

        if (request.eventType() == TrackingEventType.ARRIVEE) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "use-confirm-delivery",
                    "Use Confirm Delivery",
                    "L'arrivée doit être confirmée avec le code de confirmation fourni par l'expéditeur");
        }

        if (request.offlineTimestamp() != null
                && request.offlineTimestamp().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            auditService.log("TRACKING_EVENT", bid.getId(), "FRAUD_FUTURE_TIMESTAMP",
                    traveler.getId(), Map.of("offlineTimestamp", request.offlineTimestamp().toString()));
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-timestamp",
                    "Invalid Timestamp", "Le timestamp du scan ne peut pas être dans le futur");
        }

        TrackingEventEntity event = new TrackingEventEntity();
        event.setBidId(bid.getId());
        event.setEventType(request.eventType());
        event.setScannedAt(LocalDateTime.now(ZoneOffset.UTC));
        event.setGpsLat(request.gpsLat());
        event.setGpsLon(request.gpsLon());
        event.setPhotoUrl(request.photoUrl());
        if (request.offlineTimestamp() != null) {
            event.setOfflineTimestamp(request.offlineTimestamp());
            event.setSyncedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        trackingEventRepository.save(event);

        auditService.log("TRACKING_EVENT", event.getId(), "SCAN_" + request.eventType(),
                traveler.getId(), Map.of(
                        "bidId", bid.getId().toString(),
                        "eventType", request.eventType().name(),
                        "offline", String.valueOf(request.offlineTimestamp() != null)));

        if (request.eventType() == TrackingEventType.DEPART && bid.getConfirmationCode() == null) {
            String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
            bid.setConfirmationCode(code);
            bid.setConfirmationCodeAttempts(0);
            bidRepository.save(bid);

            notificationDispatcher.notifyUser(
                    bid.getSenderId(),
                    "Code de livraison disponible",
                    "Le voyageur est prêt à remettre votre colis. Partagez le code.",
                    Map.of("type", "CONFIRMATION_CODE_READY", "bidId", bid.getId().toString()));
            auditService.log("TRACKING_CONFIRMATION_CODE", bid.getId(), "CODE_GENERATED",
                    traveler.getId(), Map.of("bidId", bid.getId().toString()));
        }

        return toEventResponse(event, null);
    }

    @Transactional(readOnly = true)
    public List<TrackingEventResponse> getEvents(UUID bidId, String firebaseUid) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                        "Transaction introuvable"));

        UserEntity currentUser = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found",
                        "Utilisateur introuvable"));

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found",
                        "Annonce introuvable"));

        boolean isSender = currentUser.getId().equals(bid.getSenderId());
        boolean isTraveler = currentUser.getId().equals(announcement.getTravelerId());
        if (!isSender && !isTraveler) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Accès interdit à ces événements de tracking");
        }

        return trackingEventRepository.findByBidIdOrderByScannedAtAsc(bidId).stream()
                .map(e -> toEventResponse(e, resolvePhotoUrl(e.getPhotoUrl())))
                .toList();
    }

    private TrackingEventResponse toEventResponse(TrackingEventEntity e, String resolvedPhotoUrl) {
        return new TrackingEventResponse(
                e.getId(), e.getBidId(), e.getEventType().name(),
                e.getScannedAt(), e.getGpsLat(), e.getGpsLon(),
                resolvedPhotoUrl != null ? resolvedPhotoUrl : e.getPhotoUrl(),
                e.getOfflineTimestamp(), e.getCreatedAt());
    }

    private String resolvePhotoUrl(String photoKey) {
        if (photoKey == null || photoKey.startsWith("http")) return photoKey;
        return storageService.generatePresignedUrl(photoKey, Duration.ofHours(1));
    }

    public ConfirmCodeResponse getConfirmationCode(UUID bidId, String firebaseUid) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                        "Transaction introuvable"));

        UserEntity currentUser = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found",
                        "Utilisateur introuvable"));

        if (!currentUser.getId().equals(bid.getSenderId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul l'expéditeur peut consulter le code de confirmation");
        }

        return new ConfirmCodeResponse(bid.getConfirmationCode());
    }

    @Transactional
    public TrackingEventResponse confirmDelivery(UUID bidId, ConfirmDeliveryRequest request,
                                                 String firebaseUid) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found",
                        "Transaction introuvable"));

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found",
                        "Annonce introuvable"));

        UserEntity traveler = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found",
                        "Utilisateur introuvable"));

        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul le voyageur de cette annonce peut confirmer la livraison");
        }

        if (bid.getStatus() != BidStatus.ACCEPTED) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "bid-not-accepted",
                    "Bid Not Accepted", "Ce colis ne peut pas être confirmé dans son état actuel");
        }

        if (bid.getConfirmationCode() == null) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-not-generated",
                    "Code Not Generated",
                    "Le code de confirmation n'est pas encore disponible — scannez d'abord le départ du colis");
        }

        if (bid.getConfirmationCodeAttempts() >= MAX_CODE_ATTEMPTS) {
            bid.setConfirmationCode(null);
            bid.setConfirmationCodeAttempts(0);
            bidRepository.save(bid);
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "too-many-attempts",
                    "Too Many Attempts",
                    "Trop de tentatives incorrectes — contactez l'expéditeur pour obtenir le code");
        }

        if (!bid.getConfirmationCode().equals(request.confirmationCode())) {
            bid.setConfirmationCodeAttempts(bid.getConfirmationCodeAttempts() + 1);
            bidRepository.save(bid);
            int remaining = MAX_CODE_ATTEMPTS - bid.getConfirmationCodeAttempts();
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-incorrect",
                    "Code Incorrect",
                    remaining > 0
                            ? "Code incorrect — " + remaining + " tentative(s) restante(s)"
                            : "Trop de tentatives — contactez l'expéditeur pour obtenir le code");
        }

        bid.setConfirmationCode(null);
        bid.setConfirmationCodeExpiry(null);
        bid.setConfirmationCodeAttempts(0);
        bid.setStatus(BidStatus.COMPLETED);
        bidRepository.save(bid);

        TrackingEventEntity event = new TrackingEventEntity();
        event.setBidId(bid.getId());
        event.setEventType(TrackingEventType.ARRIVEE);
        event.setScannedAt(LocalDateTime.now(ZoneOffset.UTC));
        trackingEventRepository.save(event);

        eventPublisher.publishEvent(new DeliveryConfirmedEvent(bid.getId(), bid.getSenderId(), traveler.getId()));

        auditService.log("TRACKING_DELIVERY_CONFIRMED", event.getId(), "DELIVERY_CONFIRMED",
                traveler.getId(), Map.of("bidId", bid.getId().toString()));

        return toEventResponse(event, null);
    }

    private String generateQrBase64(String content) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 2,
                    EncodeHintType.CHARACTER_SET, "UTF-8"
            );
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, 400, 400, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new DonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "qr-generation-error", "QR Generation Error",
                    "Erreur lors de la génération du QR code");
        }
    }
}
