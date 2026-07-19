package com.dony.api.payments.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

@Entity
@Table(name = "processed_geniuspay_events")
public class ProcessedGeniusPayEventEntity implements Persistable<String> {

    @Id
    @Column(name = "external_reference", length = 255)
    private String externalReference;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @Transient
    private boolean isNew = true;

    @Override
    public String getId() { return externalReference; }

    @Override
    public boolean isNew() { return isNew; }

    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(LocalDateTime processedAt) { this.processedAt = processedAt; }
}
