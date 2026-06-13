package com.dony.api.auth;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
@Where(clause = "deleted_at IS NULL")
public class UserEntity extends BaseEntity {

    @Column(name = "firebase_uid", nullable = false, unique = true, length = 128)
    private String firebaseUid;

    @Column(name = "phone_number", unique = true, length = 20)
    private String phoneNumber;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "kyc_status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private KycStatus kycStatus = KycStatus.NOT_STARTED;

    @Column(name = "contact_kyc_only", nullable = false)
    private boolean contactKycOnly = true;

    @Column(name = "fcm_token", length = 512)
    private String fcmToken;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = new HashSet<>();

    @Column(name = "cancellation_count", nullable = false)
    private int cancellationCount = 0;

    // Suspension de publication de trajets (D4) — décidée par l'admin, n'impacte
    // pas le login. Distincte de UserStatus.SUSPENDED.
    @org.hibernate.annotations.ColumnDefault("false")
    @Column(name = "publishing_suspended", nullable = false)
    private boolean publishingSuspended = false;

    @Column(name = "publishing_suspended_at")
    private java.time.Instant publishingSuspendedAt;

    @Column(name = "publishing_suspended_reason", length = 255)
    private String publishingSuspendedReason;

    @Column(name = "stripe_account_id", length = 64)
    private String stripeAccountId;

    @Column(name = "stripe_account_status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private StripeAccountStatus stripeAccountStatus = StripeAccountStatus.NOT_CREATED;

    @Column(name = "stripe_account_created_at")
    private Instant stripeAccountCreatedAt;

    @Column(name = "stripe_onboarding_completed_at")
    private Instant stripeOnboardingCompletedAt;

    @Column(name = "is_pro_account", nullable = false)
    private boolean isProAccount = false;

    /** Override de taux de commission Dony (null = taux global {@code dony.commission.rate}). */
    @Column(name = "commission_rate_override")
    private java.math.BigDecimal commissionRateOverride;

    @Column(name = "pro_company_name", length = 255)
    private String proCompanyName;

    // TODO(security): proSiret stored unencrypted. Evaluate AES-256 encryption (like kyc_schema)
    // before production — SIRET can identify individual entrepreneurs under GDPR.
    @Column(name = "pro_siret", length = 14)
    private String proSiret;

    @Column(name = "country", nullable = false, length = 2)
    private String country = "FR";

    @Column(name = "average_rating", precision = 3, scale = 2)
    private BigDecimal averageRating;

    @Column(name = "kilo_pro", nullable = false)
    private boolean kiloPro = false;

    @Column(name = "kilo_pro_granted_at")
    private LocalDateTime kiloProGrantedAt;

    @Column(name = "total_trips", nullable = false)
    private int totalTrips = 0;

    @Column(name = "total_shipments", nullable = false)
    private int totalShipments = 0;

    @Column(name = "no_show_count", nullable = false)
    private int noShowCount = 0;

    @Column(name = "refused_count", nullable = false)
    private int refusedCount = 0;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount = 0;

    @Version
    @Column(name = "version")
    private Long version = 0L;

    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    @Column(name = "analytics_consent")
    private Boolean analyticsConsent; // null = jamais répondu

    @Column(name = "analytics_consent_at")
    private Instant analyticsConsentAt;

    @Column(name = "analytics_consent_version", length = 32)
    private String analyticsConsentVersion;

    @Column(name = "analytics_consent_source", length = 32)
    private String analyticsConsentSource;

    @Column(name = "stripe_customer_id", length = 255)
    private String stripeCustomerId;

    @Column(name = "commission_payment_method_id", length = 255)
    private String commissionPaymentMethodId;

    @Column(name = "commission_card_brand", length = 20)
    private String commissionCardBrand;

    @Column(name = "commission_card_last4", length = 4)
    private String commissionCardLast4;

    @Column(name = "commission_card_exp_month")
    private Integer commissionCardExpMonth;

    @Column(name = "commission_card_exp_year")
    private Integer commissionCardExpYear;

    public String getFirebaseUid() { return firebaseUid; }
    public void setFirebaseUid(String firebaseUid) { this.firebaseUid = firebaseUid; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public LocalDate getBirthDate() { return birthDate; }
    public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }

    public KycStatus getKycStatus() { return kycStatus; }
    public void setKycStatus(KycStatus kycStatus) { this.kycStatus = kycStatus; }

    public boolean isContactKycOnly() { return contactKycOnly; }
    public void setContactKycOnly(boolean contactKycOnly) { this.contactKycOnly = contactKycOnly; }

    public String getFcmToken() { return fcmToken; }
    public void setFcmToken(String fcmToken) { this.fcmToken = fcmToken; }

    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }

    public int getCancellationCount() { return cancellationCount; }
    public void setCancellationCount(int cancellationCount) { this.cancellationCount = cancellationCount; }

    public boolean isPublishingSuspended() { return publishingSuspended; }
    public void setPublishingSuspended(boolean publishingSuspended) { this.publishingSuspended = publishingSuspended; }

    public java.time.Instant getPublishingSuspendedAt() { return publishingSuspendedAt; }
    public void setPublishingSuspendedAt(java.time.Instant publishingSuspendedAt) { this.publishingSuspendedAt = publishingSuspendedAt; }

    public String getPublishingSuspendedReason() { return publishingSuspendedReason; }
    public void setPublishingSuspendedReason(String publishingSuspendedReason) { this.publishingSuspendedReason = publishingSuspendedReason; }

    public String getStripeAccountId() { return stripeAccountId; }
    public void setStripeAccountId(String stripeAccountId) { this.stripeAccountId = stripeAccountId; }

    public StripeAccountStatus getStripeAccountStatus() { return stripeAccountStatus; }
    public void setStripeAccountStatus(StripeAccountStatus stripeAccountStatus) { this.stripeAccountStatus = stripeAccountStatus; }

    public Instant getStripeAccountCreatedAt() { return stripeAccountCreatedAt; }
    public void setStripeAccountCreatedAt(Instant stripeAccountCreatedAt) { this.stripeAccountCreatedAt = stripeAccountCreatedAt; }

    public Instant getStripeOnboardingCompletedAt() { return stripeOnboardingCompletedAt; }
    public void setStripeOnboardingCompletedAt(Instant stripeOnboardingCompletedAt) { this.stripeOnboardingCompletedAt = stripeOnboardingCompletedAt; }

    public boolean isProAccount() { return isProAccount; }
    public void setProAccount(boolean proAccount) { isProAccount = proAccount; }

    public java.math.BigDecimal getCommissionRateOverride() { return commissionRateOverride; }
    public void setCommissionRateOverride(java.math.BigDecimal commissionRateOverride) { this.commissionRateOverride = commissionRateOverride; }

    public String getProCompanyName() { return proCompanyName; }
    public void setProCompanyName(String proCompanyName) { this.proCompanyName = proCompanyName; }

    public String getProSiret() { return proSiret; }
    public void setProSiret(String proSiret) { this.proSiret = proSiret; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public BigDecimal getAverageRating() { return averageRating; }
    public void setAverageRating(BigDecimal averageRating) { this.averageRating = averageRating; }

    public boolean isKiloPro() { return kiloPro; }
    public void setKiloPro(boolean kiloPro) { this.kiloPro = kiloPro; }

    public LocalDateTime getKiloProGrantedAt() { return kiloProGrantedAt; }
    public void setKiloProGrantedAt(LocalDateTime kiloProGrantedAt) { this.kiloProGrantedAt = kiloProGrantedAt; }

    public int getTotalTrips() { return totalTrips; }
    public void setTotalTrips(int totalTrips) { this.totalTrips = totalTrips; }

    public int getTotalShipments() { return totalShipments; }
    public void setTotalShipments(int totalShipments) { this.totalShipments = totalShipments; }

    public int getNoShowCount() { return noShowCount; }
    public void setNoShowCount(int noShowCount) { this.noShowCount = noShowCount; }

    public int getRefusedCount() { return refusedCount; }
    public void setRefusedCount(int refusedCount) { this.refusedCount = refusedCount; }

    public int getRatingCount() { return ratingCount; }
    public void setRatingCount(int ratingCount) { this.ratingCount = ratingCount; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Instant getDeletionRequestedAt() { return deletionRequestedAt; }
    public void setDeletionRequestedAt(Instant deletionRequestedAt) { this.deletionRequestedAt = deletionRequestedAt; }

    public Boolean getAnalyticsConsent() { return analyticsConsent; }
    public void setAnalyticsConsent(Boolean analyticsConsent) { this.analyticsConsent = analyticsConsent; }

    public Instant getAnalyticsConsentAt() { return analyticsConsentAt; }
    public void setAnalyticsConsentAt(Instant analyticsConsentAt) { this.analyticsConsentAt = analyticsConsentAt; }

    public String getAnalyticsConsentVersion() { return analyticsConsentVersion; }
    public void setAnalyticsConsentVersion(String analyticsConsentVersion) { this.analyticsConsentVersion = analyticsConsentVersion; }

    public String getAnalyticsConsentSource() { return analyticsConsentSource; }
    public void setAnalyticsConsentSource(String analyticsConsentSource) { this.analyticsConsentSource = analyticsConsentSource; }

    public String getStripeCustomerId() { return stripeCustomerId; }
    public void setStripeCustomerId(String stripeCustomerId) { this.stripeCustomerId = stripeCustomerId; }

    public String getCommissionPaymentMethodId() { return commissionPaymentMethodId; }
    public void setCommissionPaymentMethodId(String commissionPaymentMethodId) { this.commissionPaymentMethodId = commissionPaymentMethodId; }

    public String getCommissionCardBrand() { return commissionCardBrand; }
    public void setCommissionCardBrand(String commissionCardBrand) { this.commissionCardBrand = commissionCardBrand; }

    public String getCommissionCardLast4() { return commissionCardLast4; }
    public void setCommissionCardLast4(String commissionCardLast4) { this.commissionCardLast4 = commissionCardLast4; }

    public Integer getCommissionCardExpMonth() { return commissionCardExpMonth; }
    public void setCommissionCardExpMonth(Integer commissionCardExpMonth) { this.commissionCardExpMonth = commissionCardExpMonth; }

    public Integer getCommissionCardExpYear() { return commissionCardExpYear; }
    public void setCommissionCardExpYear(Integer commissionCardExpYear) { this.commissionCardExpYear = commissionCardExpYear; }
}
