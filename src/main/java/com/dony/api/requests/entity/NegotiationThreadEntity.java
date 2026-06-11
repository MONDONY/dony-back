package com.dony.api.requests.entity;

import com.dony.api.common.BaseEntity;
import com.dony.api.payments.cash.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "negotiation_threads")
@SQLRestriction("deleted_at IS NULL")
public class NegotiationThreadEntity extends BaseEntity {

    @Column(name = "package_request_id", nullable = false)
    private UUID packageRequestId;

    @Column(name = "traveler_id", nullable = false)
    private UUID travelerId;

    @Column(name = "traveler_announcement_id")
    private UUID travelerAnnouncementId;   // nullable

    @Column(name = "traveler_travel_date", nullable = false)
    private LocalDate travelerTravelDate;

    @Column(name = "traveler_available_kg", nullable = false, precision = 5, scale = 2)
    private BigDecimal travelerAvailableKg;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NegotiationThreadStatus status;

    /**
     * Optimistic-lock guard. Serializes the AWAITING_PAYMENT → ACCEPTED finalize:
     * the synchronous {@code /checkout} and the Stripe webhook can finalize the same
     * thread concurrently. With this version column the loser's commit fails (version
     * mismatch → mapped to 409) instead of re-running the finalize body and
     * double-publishing {@code PackageRequestAcceptedEvent} (duplicate bid/QR/tracking).
     */
    @jakarta.persistence.Version
    @Column(name = "version")
    private Long version = 0L;

    @Column(name = "current_price_eur", nullable = false, precision = 10, scale = 2)
    private BigDecimal currentPriceEur;

    @Column(name = "rounds_count", nullable = false)
    private Short roundsCount;

    @Column(name = "last_activity_at", nullable = false)
    private LocalDateTime lastActivityAt;

    @Column(name = "payment_intent_id", length = 255)
    private String paymentIntentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;  // null until trip-linking

    // Dony commission charge tracking for CASH negotiated threads.
    // Stored as String (not the payments/cash enums) to avoid coupling the
    // requests/ entity to the payments/ package — values mirror
    // CommissionStatus ("CHARGED"/"FAILED") and CommissionChargedVia ("WALLET"/"CARD").
    @Column(name = "commission_status", length = 20)
    private String commissionStatus;

    @Column(name = "commission_payment_intent_id", length = 255)
    private String commissionPaymentIntentId;

    @Column(name = "commission_charged_via", length = 20)
    private String commissionChargedVia;

    // Bid matérialisé une fois le thread ACCEPTED (créé par matching/ via
    // BidMaterializedEvent). Permet au mobile d'ouvrir le détail du bid
    // (suivi, no-show…) directement depuis le thread. Null tant que la
    // matérialisation n'a pas eu lieu.
    @Column(name = "materialized_bid_id")
    private UUID materializedBidId;

    // === NO-ARG CONSTRUCTOR (required by JPA) ===

    public NegotiationThreadEntity() { /* JPA */ }

    // === GETTERS ===

    public UUID getPackageRequestId() { return packageRequestId; }

    public UUID getTravelerId() { return travelerId; }

    public UUID getTravelerAnnouncementId() { return travelerAnnouncementId; }

    public LocalDate getTravelerTravelDate() { return travelerTravelDate; }

    public BigDecimal getTravelerAvailableKg() { return travelerAvailableKg; }

    public NegotiationThreadStatus getStatus() { return status; }

    public BigDecimal getCurrentPriceEur() { return currentPriceEur; }

    public Short getRoundsCount() { return roundsCount; }

    public LocalDateTime getLastActivityAt() { return lastActivityAt; }

    public String getPaymentIntentId() { return paymentIntentId; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }

    public String getCommissionStatus() { return commissionStatus; }

    public String getCommissionPaymentIntentId() { return commissionPaymentIntentId; }

    public String getCommissionChargedVia() { return commissionChargedVia; }

    public UUID getMaterializedBidId() { return materializedBidId; }

    // === SETTERS ===

    public void setPackageRequestId(UUID packageRequestId) { this.packageRequestId = packageRequestId; }

    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }

    public void setTravelerAnnouncementId(UUID travelerAnnouncementId) { this.travelerAnnouncementId = travelerAnnouncementId; }

    public void setTravelerTravelDate(LocalDate travelerTravelDate) { this.travelerTravelDate = travelerTravelDate; }

    public void setTravelerAvailableKg(BigDecimal travelerAvailableKg) { this.travelerAvailableKg = travelerAvailableKg; }

    public void setStatus(NegotiationThreadStatus status) { this.status = status; }

    public void setCurrentPriceEur(BigDecimal currentPriceEur) { this.currentPriceEur = currentPriceEur; }

    public void setRoundsCount(Short roundsCount) { this.roundsCount = roundsCount; }

    public void setLastActivityAt(LocalDateTime lastActivityAt) { this.lastActivityAt = lastActivityAt; }

    public void setPaymentIntentId(String paymentIntentId) { this.paymentIntentId = paymentIntentId; }

    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public void setCommissionStatus(String commissionStatus) { this.commissionStatus = commissionStatus; }

    public void setCommissionPaymentIntentId(String commissionPaymentIntentId) { this.commissionPaymentIntentId = commissionPaymentIntentId; }

    public void setCommissionChargedVia(String commissionChargedVia) { this.commissionChargedVia = commissionChargedVia; }

    public void setMaterializedBidId(UUID materializedBidId) { this.materializedBidId = materializedBidId; }
}
