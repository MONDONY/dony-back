package com.dony.api.automation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "automation_history")
public class AutomationHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "traveler_id", nullable = false)
    private UUID travelerId;

    @Column(name = "rule_id")
    private UUID ruleId;

    @Column(name = "rule_label", nullable = false, length = 255)
    private String ruleLabel;

    @Column(name = "bid_id")
    private UUID bidId;

    @Column(name = "trip_id")
    private UUID tripId;

    @Column(name = "action_taken", nullable = false, length = 255)
    private String actionTaken;

    @Column(name = "result", nullable = false, length = 20)
    private String result;

    @Column(name = "error_detail")
    private String errorDetail;

    @Column(name = "triggered_at", nullable = false)
    private LocalDateTime triggeredAt;

    public AutomationHistoryEntity() {}

    public UUID getId() { return id; }

    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }

    public UUID getRuleId() { return ruleId; }
    public void setRuleId(UUID ruleId) { this.ruleId = ruleId; }

    public String getRuleLabel() { return ruleLabel; }
    public void setRuleLabel(String ruleLabel) { this.ruleLabel = ruleLabel; }

    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }

    public UUID getTripId() { return tripId; }
    public void setTripId(UUID tripId) { this.tripId = tripId; }

    public String getActionTaken() { return actionTaken; }
    public void setActionTaken(String actionTaken) { this.actionTaken = actionTaken; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }

    public LocalDateTime getTriggeredAt() { return triggeredAt; }
    public void setTriggeredAt(LocalDateTime triggeredAt) { this.triggeredAt = triggeredAt; }
}
