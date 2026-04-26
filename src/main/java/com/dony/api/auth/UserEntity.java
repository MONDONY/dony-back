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
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
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
    private KycStatus kycStatus = KycStatus.PENDING;

    @Column(name = "fcm_token", length = 512)
    private String fcmToken;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = new HashSet<>();

    @Column(name = "cancellation_count", nullable = false)
    private int cancellationCount = 0;

    @Column(name = "stripe_account_id", length = 64)
    private String stripeAccountId;

    @Column(name = "stripe_onboarded", nullable = false)
    private boolean stripeOnboarded = false;

    @Column(name = "average_rating", precision = 3, scale = 2)
    private BigDecimal averageRating;

    @Column(name = "kilo_pro", nullable = false)
    private boolean kiloPro = false;

    @Column(name = "kilo_pro_granted_at")
    private LocalDateTime kiloProGrantedAt;

    @Column(name = "no_show_count", nullable = false)
    private int noShowCount = 0;

    @Column(name = "refused_count", nullable = false)
    private int refusedCount = 0;

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

    public String getFcmToken() { return fcmToken; }
    public void setFcmToken(String fcmToken) { this.fcmToken = fcmToken; }

    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }

    public int getCancellationCount() { return cancellationCount; }
    public void setCancellationCount(int cancellationCount) { this.cancellationCount = cancellationCount; }

    public String getStripeAccountId() { return stripeAccountId; }
    public void setStripeAccountId(String stripeAccountId) { this.stripeAccountId = stripeAccountId; }

    public boolean isStripeOnboarded() { return stripeOnboarded; }
    public void setStripeOnboarded(boolean stripeOnboarded) { this.stripeOnboarded = stripeOnboarded; }

    public BigDecimal getAverageRating() { return averageRating; }
    public void setAverageRating(BigDecimal averageRating) { this.averageRating = averageRating; }

    public boolean isKiloPro() { return kiloPro; }
    public void setKiloPro(boolean kiloPro) { this.kiloPro = kiloPro; }

    public LocalDateTime getKiloProGrantedAt() { return kiloProGrantedAt; }
    public void setKiloProGrantedAt(LocalDateTime kiloProGrantedAt) { this.kiloProGrantedAt = kiloProGrantedAt; }

    public int getNoShowCount() { return noShowCount; }
    public void setNoShowCount(int noShowCount) { this.noShowCount = noShowCount; }

    public int getRefusedCount() { return refusedCount; }
    public void setRefusedCount(int refusedCount) { this.refusedCount = refusedCount; }
}
