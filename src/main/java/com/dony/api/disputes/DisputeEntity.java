package com.dony.api.disputes;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "disputes")
@Where(clause = "deleted_at IS NULL")
public class DisputeEntity extends BaseEntity {

    @Column(name = "bid_id")
    private UUID bidId;

    @Column(name = "sender_id")
    private UUID senderId;

    @Column(name = "traveler_id")
    private UUID travelerId;

    @Column(name = "type", length = 50)
    private String type;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "OPEN";

    @Column(name = "refund_frozen", nullable = false)
    private boolean refundFrozen = false;

    @Column(name = "resolution", length = 50)
    private String resolution;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "beneficiary_user_id")
    private UUID beneficiaryUserId;

    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }

    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }

    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isRefundFrozen() { return refundFrozen; }
    public void setRefundFrozen(boolean refundFrozen) { this.refundFrozen = refundFrozen; }

    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }

    public UUID getBeneficiaryUserId() { return beneficiaryUserId; }
    public void setBeneficiaryUserId(UUID beneficiaryUserId) { this.beneficiaryUserId = beneficiaryUserId; }
}
