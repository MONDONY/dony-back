package com.dony.api.kyc;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "kyc_verifications", schema = "kyc_schema")
public class KycVerificationEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "stripe_verification_session_id", length = 255)
    private String stripeVerificationSessionId;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private KycVerificationStatus status = KycVerificationStatus.PENDING;

    // Stored encrypted via EncryptionService (applied in KycService layer)
    @Column(name = "id_document_encrypted", columnDefinition = "TEXT")
    private String idDocumentEncrypted;

    // Stored encrypted via EncryptionService
    @Column(name = "selfie_url", length = 1024)
    private String selfieUrl;

    @Column(name = "rejection_reason", length = 512)
    private String rejectionReason;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getStripeVerificationSessionId() { return stripeVerificationSessionId; }
    public void setStripeVerificationSessionId(String id) { this.stripeVerificationSessionId = id; }

    public KycVerificationStatus getStatus() { return status; }
    public void setStatus(KycVerificationStatus status) { this.status = status; }

    public String getIdDocumentEncrypted() { return idDocumentEncrypted; }
    public void setIdDocumentEncrypted(String idDocumentEncrypted) { this.idDocumentEncrypted = idDocumentEncrypted; }

    public String getSelfieUrl() { return selfieUrl; }
    public void setSelfieUrl(String selfieUrl) { this.selfieUrl = selfieUrl; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
}
