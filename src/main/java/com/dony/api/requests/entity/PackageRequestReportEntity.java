package com.dony.api.requests.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Signalement d'une demande d'envoi par un utilisateur (modération). Pas de soft-delete. */
@Entity
@Table(name = "package_request_reports")
public class PackageRequestReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "package_request_id", nullable = false)
    private UUID packageRequestId;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Column(name = "reason", nullable = false, length = 50)
    private String reason;

    @Column(name = "details", length = 500)
    private String details;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PackageRequestReportEntity() {
    }

    public PackageRequestReportEntity(UUID packageRequestId, UUID reporterId, String reason, String details) {
        this.packageRequestId = packageRequestId;
        this.reporterId = reporterId;
        this.reason = reason;
        this.details = details;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public UUID getId() { return id; }
    public UUID getPackageRequestId() { return packageRequestId; }
    public UUID getReporterId() { return reporterId; }
    public String getReason() { return reason; }
    public String getDetails() { return details; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
