package com.dony.api.tracking;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.payments.PaymentEntity;
import com.dony.api.payments.PaymentRepository;
import com.dony.api.payments.PaymentStatus;
import com.dony.api.tracking.dto.QrCodeResponse;
import com.dony.api.tracking.dto.TrackingSearchResponse;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class TrackingService {

    private final BidRepository bidRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;

    @Value("${app.base-url}")
    private String appBaseUrl;

    public TrackingService(BidRepository bidRepository,
                           PaymentRepository paymentRepository,
                           UserRepository userRepository,
                           AnnouncementRepository announcementRepository) {
        this.bidRepository = bidRepository;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.announcementRepository = announcementRepository;
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
            // ACCEPTED
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
