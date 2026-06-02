package com.dony.api.matching;

import com.dony.api.common.BaseEntity;
import com.dony.api.payments.cash.CommissionChargedVia;
import com.dony.api.payments.cash.CommissionStatus;
import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.matching.BidPricingMode;
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

    @Column(name = "weight_kg", precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "declared_value_eur", precision = 10, scale = 2)
    private BigDecimal declaredValueEur;

    /**
     * If non-null, this bid was created from the package_request marketplace
     * flow (NegotiationThread → ACCEPTED) rather than the classic announce-bid flow.
     * Lets PackageRequest.completeDetails sync recipient + declared value
     * back onto the bid.
     */
    @Column(name = "linked_negotiation_thread_id")
    private UUID linkedNegotiationThreadId;

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

    @Column(name = "tracking_number", unique = true, length = 12)
    private String trackingNumber;

    @Column(name = "tracking_token", unique = true, length = 36)
    private String trackingToken;

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

    @Column(name = "confirmation_code", length = 6)
    private String confirmationCode;

    @Column(name = "confirmation_code_expiry")
    private LocalDateTime confirmationCodeExpiry;

    @Column(name = "confirmation_code_attempts", nullable = false)
    private int confirmationCodeAttempts = 0;

    @Column(name = "confirmation_code_refresh_count", nullable = false)
    private int confirmationCodeRefreshCount = 0;

    @Column(name = "confirmation_code_refresh_window_start")
    private LocalDateTime confirmationCodeRefreshWindowStart;

    @Column(name = "h2_alert_sent_at")
    private LocalDateTime h2AlertSentAt;

    @Column(name = "deleted_by_sender", nullable = false)
    private boolean deletedBySender = false;

    @Column(name = "deleted_by_traveler", nullable = false)
    private boolean deletedByTraveler = false;

    @Column(name = "refusal_reason", columnDefinition = "TEXT")
    private String refusalReason;

    @Column(name = "refusal_photo_url", columnDefinition = "TEXT")
    private String refusalPhotoUrl;

    @Column(name = "no_show_at")
    private LocalDateTime noShowAt;

    @Column(name = "payment_intent_id", length = 255)
    private String paymentIntentId;

    @Column(name = "awaiting_payment_expires_at")
    private LocalDateTime awaitingPaymentExpiresAt;

    @Column(name = "shipment_counted", nullable = false)
    private boolean shipmentCounted = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod = PaymentMethod.STRIPE;

    @Column(name = "mobile_money_phone", length = 30)
    private String mobileMoneyPhone;

    @Column(name = "mobile_money_country_code", length = 5)
    private String mobileMoneyCountryCode;

    @Column(name = "commission_payment_intent_id", length = 255)
    private String commissionPaymentIntentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "commission_status", length = 20)
    private CommissionStatus commissionStatus;

    @Column(name = "commission_retry_count", nullable = false)
    private int commissionRetryCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "commission_charged_via", length = 10)
    private CommissionChargedVia commissionChargedVia;

    /** Taux de commission effectif figé à la création du paiement (snapshot). */
    @Column(name = "commission_rate")
    private java.math.BigDecimal commissionRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_mode", nullable = false, length = 10)
    private BidPricingMode pricingMode = BidPricingMode.KG;

    public UUID getAnnouncementId() { return announcementId; }
    public void setAnnouncementId(UUID announcementId) { this.announcementId = announcementId; }

    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }

    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal weightKg) { this.weightKg = weightKg; }

    public BigDecimal getDeclaredValueEur() { return declaredValueEur; }
    public void setDeclaredValueEur(BigDecimal declaredValueEur) { this.declaredValueEur = declaredValueEur; }

    public UUID getLinkedNegotiationThreadId() { return linkedNegotiationThreadId; }
    public void setLinkedNegotiationThreadId(UUID id) { this.linkedNegotiationThreadId = id; }

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

    public String getTrackingNumber() { return trackingNumber; }
    public void setTrackingNumber(String trackingNumber) { this.trackingNumber = trackingNumber; }

    public String getTrackingToken() { return trackingToken; }
    public void setTrackingToken(String trackingToken) { this.trackingToken = trackingToken; }

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

    public String getConfirmationCode() { return confirmationCode; }
    public void setConfirmationCode(String confirmationCode) { this.confirmationCode = confirmationCode; }

    public LocalDateTime getConfirmationCodeExpiry() { return confirmationCodeExpiry; }
    public void setConfirmationCodeExpiry(LocalDateTime confirmationCodeExpiry) { this.confirmationCodeExpiry = confirmationCodeExpiry; }

    public int getConfirmationCodeAttempts() { return confirmationCodeAttempts; }
    public void setConfirmationCodeAttempts(int confirmationCodeAttempts) { this.confirmationCodeAttempts = confirmationCodeAttempts; }

    public int getConfirmationCodeRefreshCount() { return confirmationCodeRefreshCount; }
    public void setConfirmationCodeRefreshCount(int confirmationCodeRefreshCount) { this.confirmationCodeRefreshCount = confirmationCodeRefreshCount; }

    public LocalDateTime getConfirmationCodeRefreshWindowStart() { return confirmationCodeRefreshWindowStart; }
    public void setConfirmationCodeRefreshWindowStart(LocalDateTime confirmationCodeRefreshWindowStart) { this.confirmationCodeRefreshWindowStart = confirmationCodeRefreshWindowStart; }

    public LocalDateTime getH2AlertSentAt() { return h2AlertSentAt; }
    public void setH2AlertSentAt(LocalDateTime h2AlertSentAt) { this.h2AlertSentAt = h2AlertSentAt; }

    public boolean isDeletedBySender() { return deletedBySender; }
    public void setDeletedBySender(boolean deletedBySender) { this.deletedBySender = deletedBySender; }

    public boolean isDeletedByTraveler() { return deletedByTraveler; }
    public void setDeletedByTraveler(boolean deletedByTraveler) { this.deletedByTraveler = deletedByTraveler; }

    public String getRefusalReason() { return refusalReason; }
    public void setRefusalReason(String refusalReason) { this.refusalReason = refusalReason; }

    public String getRefusalPhotoUrl() { return refusalPhotoUrl; }
    public void setRefusalPhotoUrl(String refusalPhotoUrl) { this.refusalPhotoUrl = refusalPhotoUrl; }

    public LocalDateTime getNoShowAt() { return noShowAt; }
    public void setNoShowAt(LocalDateTime noShowAt) { this.noShowAt = noShowAt; }

    public String getPaymentIntentId() { return paymentIntentId; }
    public void setPaymentIntentId(String paymentIntentId) { this.paymentIntentId = paymentIntentId; }

    public LocalDateTime getAwaitingPaymentExpiresAt() { return awaitingPaymentExpiresAt; }
    public void setAwaitingPaymentExpiresAt(LocalDateTime awaitingPaymentExpiresAt) { this.awaitingPaymentExpiresAt = awaitingPaymentExpiresAt; }

    public boolean isShipmentCounted() { return shipmentCounted; }
    public void setShipmentCounted(boolean shipmentCounted) { this.shipmentCounted = shipmentCounted; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getCommissionPaymentIntentId() { return commissionPaymentIntentId; }
    public void setCommissionPaymentIntentId(String commissionPaymentIntentId) { this.commissionPaymentIntentId = commissionPaymentIntentId; }

    public CommissionStatus getCommissionStatus() { return commissionStatus; }
    public void setCommissionStatus(CommissionStatus commissionStatus) { this.commissionStatus = commissionStatus; }

    public int getCommissionRetryCount() { return commissionRetryCount; }
    public void setCommissionRetryCount(int commissionRetryCount) { this.commissionRetryCount = commissionRetryCount; }

    public CommissionChargedVia getCommissionChargedVia() { return commissionChargedVia; }
    public void setCommissionChargedVia(CommissionChargedVia commissionChargedVia) { this.commissionChargedVia = commissionChargedVia; }

    public java.math.BigDecimal getCommissionRate() { return commissionRate; }
    public void setCommissionRate(java.math.BigDecimal commissionRate) { this.commissionRate = commissionRate; }

    public BidPricingMode getPricingMode() { return pricingMode; }
    public void setPricingMode(BidPricingMode pricingMode) { this.pricingMode = pricingMode; }

    public String getMobileMoneyPhone() { return mobileMoneyPhone; }
    public void setMobileMoneyPhone(String mobileMoneyPhone) { this.mobileMoneyPhone = mobileMoneyPhone; }

    public String getMobileMoneyCountryCode() { return mobileMoneyCountryCode; }
    public void setMobileMoneyCountryCode(String mobileMoneyCountryCode) { this.mobileMoneyCountryCode = mobileMoneyCountryCode; }
}
