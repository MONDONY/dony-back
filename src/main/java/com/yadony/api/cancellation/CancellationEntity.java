package com.yadony.api.cancellation;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "cancellations",
        uniqueConstraints = @UniqueConstraint(name = "uq_cancellations_bid_id_scope", columnNames = {"bid_id", "scope"}))
public class CancellationEntity extends BaseEntity {

    // Unicité désormais portée par (bid_id, scope) — voir uniqueConstraints
    // ci-dessus et migration V173. Ne plus mettre `unique = true` ici seul,
    // sinon Hibernate (ddl-auto=create, tests H2) régénère une contrainte
    // mono-colonne qui interdirait un HANDOVER + un DELIVERY pour le même bid.
    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Column(name = "cancelled_by", nullable = false)
    private UUID cancelledBy;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "refund_status", nullable = false, length = 20)
    private String refundStatus = "PENDING";

    @Column(name = "rematch_status", nullable = false, length = 20)
    private String rematchStatus = "NONE";

    @Enumerated(EnumType.STRING)
    @Column(name = "no_show_status", nullable = false, length = 25)
    private CancellationStatus noShowStatus = CancellationStatus.CONFIRMED;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private CancellationScope scope = CancellationScope.HANDOVER;

    @Column(name = "contestation_deadline")
    private OffsetDateTime contestationDeadline;

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

    public CancellationStatus getNoShowStatus() { return noShowStatus; }
    public void setNoShowStatus(CancellationStatus noShowStatus) { this.noShowStatus = noShowStatus; }

    public CancellationScope getScope() { return scope; }
    public void setScope(CancellationScope scope) { this.scope = scope; }

    public OffsetDateTime getContestationDeadline() { return contestationDeadline; }
    public void setContestationDeadline(OffsetDateTime contestationDeadline) { this.contestationDeadline = contestationDeadline; }
}
