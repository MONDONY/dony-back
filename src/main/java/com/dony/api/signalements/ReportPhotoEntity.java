package com.dony.api.signalements;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.util.UUID;

@Entity
@Table(name = "report_photos")
@Where(clause = "deleted_at IS NULL")
public class ReportPhotoEntity extends BaseEntity {

    @Column(name = "report_id", nullable = false)
    private UUID reportId;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    public UUID getReportId() { return reportId; }
    public void setReportId(UUID reportId) { this.reportId = reportId; }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
}
