package com.dony.api.payments.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "processed_geniuspay_events")
public class ProcessedGeniusPayEventEntity {

    @Id
    @Column(name = "external_reference", length = 255)
    private String externalReference;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(LocalDateTime processedAt) { this.processedAt = processedAt; }
}
