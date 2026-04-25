package com.dony.api.matching;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "bids")
@Where(clause = "deleted_at IS NULL")
public class BidEntity extends BaseEntity {

    @Column(name = "announcement_id", nullable = false)
    private UUID announcementId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "weight_kg", nullable = false, precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "declared_value_eur", nullable = false, precision = 10, scale = 2)
    private BigDecimal declaredValueEur;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "content_category", length = 50)
    private String contentCategory;

    @Column(name = "recipient_name", length = 200)
    private String recipientName;

    @Column(name = "recipient_phone", length = 30)
    private String recipientPhone;

    @Column(name = "disclaimer_signed_at")
    private LocalDateTime disclaimerSignedAt;

    @Column(name = "disclaimer_signed_ip", length = 45)
    private String disclaimerSignedIp;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private BidStatus status = BidStatus.PENDING;

    @Column(name = "qr_token", unique = true, length = 255)
    private String qrToken;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "handover_location", columnDefinition = "TEXT")
    private String handoverLocation;

    @Column(name = "handover_window_start")
    private LocalDateTime handoverWindowStart;

    @Column(name = "handover_window_end")
    private LocalDateTime handoverWindowEnd;

    @Column(name = "voyageur_confirmed", nullable = false)
    private boolean voyageurConfirmed = false;

    @Column(name = "h2_alert_sent_at")
    private LocalDateTime h2AlertSentAt;

    @Column(name = "deleted_by_sender", nullable = false)
    private boolean deletedBySender = false;

    @Column(name = "deleted_by_traveler", nullable = false)
    private boolean deletedByTraveler = false;

    public UUID getAnnouncementId() { return announcementId; }
    public void setAnnouncementId(UUID announcementId) { this.announcementId = announcementId; }

    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }

    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal weightKg) { this.weightKg = weightKg; }

    public BigDecimal getDeclaredValueEur() { return declaredValueEur; }
    public void setDeclaredValueEur(BigDecimal declaredValueEur) { this.declaredValueEur = declaredValueEur; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getContentCategory() { return contentCategory; }
    public void setContentCategory(String contentCategory) { this.contentCategory = contentCategory; }

    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }

    public String getRecipientPhone() { return recipientPhone; }
    public void setRecipientPhone(String recipientPhone) { this.recipientPhone = recipientPhone; }

    public LocalDateTime getDisclaimerSignedAt() { return disclaimerSignedAt; }
    public void setDisclaimerSignedAt(LocalDateTime disclaimerSignedAt) { this.disclaimerSignedAt = disclaimerSignedAt; }

    public String getDisclaimerSignedIp() { return disclaimerSignedIp; }
    public void setDisclaimerSignedIp(String disclaimerSignedIp) { this.disclaimerSignedIp = disclaimerSignedIp; }

    public BidStatus getStatus() { return status; }
    public void setStatus(BidStatus status) { this.status = status; }

    public String getQrToken() { return qrToken; }
    public void setQrToken(String qrToken) { this.qrToken = qrToken; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public String getHandoverLocation() { return handoverLocation; }
    public void setHandoverLocation(String handoverLocation) { this.handoverLocation = handoverLocation; }

    public LocalDateTime getHandoverWindowStart() { return handoverWindowStart; }
    public void setHandoverWindowStart(LocalDateTime handoverWindowStart) { this.handoverWindowStart = handoverWindowStart; }

    public LocalDateTime getHandoverWindowEnd() { return handoverWindowEnd; }
    public void setHandoverWindowEnd(LocalDateTime handoverWindowEnd) { this.handoverWindowEnd = handoverWindowEnd; }

    public boolean isVoyageurConfirmed() { return voyageurConfirmed; }
    public void setVoyageurConfirmed(boolean voyageurConfirmed) { this.voyageurConfirmed = voyageurConfirmed; }

    public LocalDateTime getH2AlertSentAt() { return h2AlertSentAt; }
    public void setH2AlertSentAt(LocalDateTime h2AlertSentAt) { this.h2AlertSentAt = h2AlertSentAt; }

    public boolean isDeletedBySender() { return deletedBySender; }
    public void setDeletedBySender(boolean deletedBySender) { this.deletedBySender = deletedBySender; }

    public boolean isDeletedByTraveler() { return deletedByTraveler; }
    public void setDeletedByTraveler(boolean deletedByTraveler) { this.deletedByTraveler = deletedByTraveler; }
}
