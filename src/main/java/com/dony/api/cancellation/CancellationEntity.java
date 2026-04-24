package com.dony.api.cancellation;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "cancellations")
public class CancellationEntity extends BaseEntity {

    @Column(name = "bid_id", nullable = false, unique = true)
    private UUID bidId;

    @Column(name = "cancelled_by", nullable = false)
    private UUID cancelledBy;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "refund_status", nullable = false, length = 20)
    private String refundStatus = "PENDING";

    @Column(name = "rematch_status", nullable = false, length = 20)
    private String rematchStatus = "NONE";

    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }

    public UUID getCancelledBy() { return cancelledBy; }
    public void setCancelledBy(UUID cancelledBy) { this.cancelledBy = cancelledBy; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getRefundStatus() { return refundStatus; }
    public void setRefundStatus(String refundStatus) { this.refundStatus = refundStatus; }

    public String getRematchStatus() { return rematchStatus; }
    public void setRematchStatus(String rematchStatus) { this.rematchStatus = rematchStatus; }
}
