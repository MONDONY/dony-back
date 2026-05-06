# Account Deletion (RGPD + Grace Period) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implémenter la suppression de compte avec période de grâce de 30 jours (PENDING_DELETION → BANNED à J+30), archivage immédiat des annonces/bids, et endpoint de réactivation — en corrigeant les violations d'architecture et bugs existants.

**Architecture:** `DELETE /auth/me` passe le compte en `PENDING_DELETION` et publie `AccountDeletionRequestedEvent` ; `matching/` écoute et annule les annonces/bids ouverts. Un scheduler nightly finalise la pseudonymisation RGPD à J+30. `POST /auth/me/reactivate` annule la demande si appelé dans les 30 jours.

**Tech Stack:** Spring Boot 3.4.x, Java 21, JPA/Hibernate, Spring Application Events, Mockito + JUnit 5 (AssertJ), H2 (tests), PostgreSQL 16 (prod)

---

## File Map

| Action | Fichier |
|--------|---------|
| Créer | `src/main/resources/db/migration/V50__users_add_deletion_requested_at.sql` |
| Modifier | `src/main/java/com/dony/api/auth/UserStatus.java` |
| Modifier | `src/main/java/com/dony/api/auth/UserEntity.java` |
| Modifier | `src/main/java/com/dony/api/auth/UserRepository.java` |
| Créer | `src/main/java/com/dony/api/auth/events/AccountDeletionRequestedEvent.java` |
| Modifier | `src/main/java/com/dony/api/auth/UserService.java` |
| Modifier | `src/main/java/com/dony/api/auth/AuthService.java` |
| Modifier | `src/main/java/com/dony/api/auth/AuthController.java` |
| Créer | `src/main/java/com/dony/api/auth/AccountDeletionScheduler.java` |
| Modifier | `src/main/java/com/dony/api/matching/AnnouncementRepository.java` |
| Modifier | `src/main/java/com/dony/api/matching/BidRepository.java` |
| Créer | `src/main/java/com/dony/api/matching/AccountDeletionListener.java` |
| Créer | `src/test/java/com/dony/api/auth/UserServiceDeleteAccountTest.java` |
| Créer | `src/test/java/com/dony/api/auth/AccountDeletionSchedulerTest.java` |
| Créer | `src/test/java/com/dony/api/matching/AccountDeletionListenerTest.java` |

---

## Task 1 : Migration Flyway V50

**Files:**
- Create: `src/main/resources/db/migration/V50__users_add_deletion_requested_at.sql`

- [ ] **Step 1: Créer la migration**

```sql
-- V50__users_add_deletion_requested_at.sql
ALTER TABLE users ADD COLUMN IF NOT EXISTS deletion_requested_at TIMESTAMPTZ;
```

> Note : `PENDING_DELETION` est stocké comme VARCHAR dans la colonne `status` existante — aucune modification de colonne nécessaire.

- [ ] **Step 2: Vérifier que la migration est valide**

```bash
cd dony-back
./mvnw flyway:validate -Dspring.profiles.active=dev
```

Expected : `Flyway validation successful`

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V50__users_add_deletion_requested_at.sql
git commit -m "feat: add deletion_requested_at column to users (V50)"
```

---

## Task 2 : UserStatus + UserEntity — modèle de données

**Files:**
- Modify: `src/main/java/com/dony/api/auth/UserStatus.java`
- Modify: `src/main/java/com/dony/api/auth/UserEntity.java`

- [ ] **Step 1: Ajouter PENDING_DELETION à UserStatus**

Remplacer le contenu de `UserStatus.java` :

```java
package com.dony.api.auth;

public enum UserStatus {
    ACTIVE, SUSPENDED, BANNED, PENDING_DELETION
}
```

- [ ] **Step 2: Ajouter deletionRequestedAt à UserEntity**

Dans `UserEntity.java`, après le champ `version` (ligne ~119), ajouter :

```java
@Column(name = "deletion_requested_at")
private Instant deletionRequestedAt;
```

Puis à la fin des getters/setters (avant la dernière `}`), ajouter :

```java
public Instant getDeletionRequestedAt() { return deletionRequestedAt; }
public void setDeletionRequestedAt(Instant deletionRequestedAt) { this.deletionRequestedAt = deletionRequestedAt; }
```

- [ ] **Step 3: Compiler pour vérifier**

```bash
./mvnw compile -q
```

Expected : BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dony/api/auth/UserStatus.java \
        src/main/java/com/dony/api/auth/UserEntity.java
git commit -m "feat: add PENDING_DELETION status and deletionRequestedAt to UserEntity"
```

---

## Task 3 : UserRepository — nouvelle query

**Files:**
- Modify: `src/main/java/com/dony/api/auth/UserRepository.java`

- [ ] **Step 1: Ajouter findByStatusAndDeletionRequestedAtBefore**

Dans `UserRepository.java`, ajouter l'import et la méthode :

```java
import java.time.Instant;
import java.util.List;
```

```java
List<UserEntity> findByStatusAndDeletionRequestedAtBefore(UserStatus status, Instant cutoff);
```

Spring Data JPA dérive automatiquement la query depuis le nom de la méthode — pas besoin de `@Query`.

- [ ] **Step 2: Compiler**

```bash
./mvnw compile -q
```

Expected : BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/dony/api/auth/UserRepository.java
git commit -m "feat: add findByStatusAndDeletionRequestedAtBefore to UserRepository"
```

---

## Task 4 : AccountDeletionRequestedEvent

**Files:**
- Create: `src/main/java/com/dony/api/auth/events/AccountDeletionRequestedEvent.java`

- [ ] **Step 1: Créer l'event**

```java
package com.dony.api.auth.events;

import java.util.UUID;

public class AccountDeletionRequestedEvent {

    private final UUID userId;

    public AccountDeletionRequestedEvent(UUID userId) {
        this.userId = userId;
    }

    public UUID getUserId() { return userId; }
}
```

- [ ] **Step 2: Compiler**

```bash
./mvnw compile -q
```

Expected : BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/dony/api/auth/events/AccountDeletionRequestedEvent.java
git commit -m "feat: add AccountDeletionRequestedEvent"
```

---

## Task 5 : Repository queries dans matching/

**Files:**
- Modify: `src/main/java/com/dony/api/matching/AnnouncementRepository.java`
- Modify: `src/main/java/com/dony/api/matching/BidRepository.java`

- [ ] **Step 1: Ajouter cancelOpenAnnouncementsByUserId à AnnouncementRepository**

Ajouter les imports nécessaires :

```java
import org.springframework.data.jpa.repository.Modifying;
```

Ajouter la méthode dans `AnnouncementRepository` :

```java
@Modifying
@Query("UPDATE AnnouncementEntity a SET a.status = com.dony.api.matching.AnnouncementStatus.CANCELLED " +
       "WHERE a.travelerId = :userId AND a.status IN " +
       "(com.dony.api.matching.AnnouncementStatus.ACTIVE, com.dony.api.matching.AnnouncementStatus.FULL)")
int cancelOpenAnnouncementsByUserId(@Param("userId") UUID userId);
```

> `AnnouncementStatus` n'a pas de valeur `ARCHIVED` — on utilise `CANCELLED` qui est sémantiquement correct pour une suppression de compte.

- [ ] **Step 2: Ajouter cancelOpenBidsByUserId à BidRepository**

Deux méthodes `@Modifying` : une pour les bids du sender, une pour les bids liés aux annonces du traveler.

Ajouter l'import :

```java
import org.springframework.data.jpa.repository.Modifying;
```

Ajouter les méthodes dans `BidRepository` :

```java
@Modifying
@Query("UPDATE BidEntity b SET b.status = com.dony.api.matching.BidStatus.CANCELLED " +
       "WHERE b.senderId = :userId " +
       "AND b.status IN (com.dony.api.matching.BidStatus.PENDING, " +
       "                 com.dony.api.matching.BidStatus.ACCEPTED, " +
       "                 com.dony.api.matching.BidStatus.AWAITING_PAYMENT)")
int cancelOpenSenderBidsByUserId(@Param("userId") UUID userId);

@Modifying
@Query("UPDATE BidEntity b SET b.status = com.dony.api.matching.BidStatus.CANCELLED " +
       "WHERE b.announcementId IN " +
       "  (SELECT a.id FROM AnnouncementEntity a WHERE a.travelerId = :userId) " +
       "AND b.status IN (com.dony.api.matching.BidStatus.PENDING, " +
       "                 com.dony.api.matching.BidStatus.ACCEPTED, " +
       "                 com.dony.api.matching.BidStatus.AWAITING_PAYMENT)")
int cancelOpenTravelerBidsByUserId(@Param("userId") UUID userId);
```

- [ ] **Step 3: Compiler**

```bash
./mvnw compile -q
```

Expected : BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dony/api/matching/AnnouncementRepository.java \
        src/main/java/com/dony/api/matching/BidRepository.java
git commit -m "feat: add bulk-cancel queries for account deletion in AnnouncementRepository and BidRepository"
```

---

## Task 6 : AccountDeletionListener (TDD)

**Files:**
- Create: `src/main/java/com/dony/api/matching/AccountDeletionListener.java`
- Create: `src/test/java/com/dony/api/matching/AccountDeletionListenerTest.java`

- [ ] **Step 1: Écrire le test en premier**

```java
package com.dony.api.matching;

import com.dony.api.auth.events.AccountDeletionRequestedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountDeletionListener — tests unitaires")
class AccountDeletionListenerTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private BidRepository bidRepository;

    @InjectMocks private AccountDeletionListener listener;

    @Test
    @DisplayName("event reçu → annonces ACTIVE/FULL annulées")
    void onDeletion_cancelsOpenAnnouncements() {
        UUID userId = UUID.randomUUID();
        listener.onDeletionRequested(new AccountDeletionRequestedEvent(userId));
        verify(announcementRepository).cancelOpenAnnouncementsByUserId(userId);
    }

    @Test
    @DisplayName("event reçu → bids sender ouverts annulés")
    void onDeletion_cancelsSenderBids() {
        UUID userId = UUID.randomUUID();
        listener.onDeletionRequested(new AccountDeletionRequestedEvent(userId));
        verify(bidRepository).cancelOpenSenderBidsByUserId(userId);
    }

    @Test
    @DisplayName("event reçu → bids traveler ouverts annulés")
    void onDeletion_cancelsTravelerBids() {
        UUID userId = UUID.randomUUID();
        listener.onDeletionRequested(new AccountDeletionRequestedEvent(userId));
        verify(bidRepository).cancelOpenTravelerBidsByUserId(userId);
    }
}
```

- [ ] **Step 2: Lancer le test — vérifier qu'il échoue**

```bash
./mvnw test -Dtest=AccountDeletionListenerTest -q 2>&1 | tail -5
```

Expected : FAIL — `AccountDeletionListener` n'existe pas encore

- [ ] **Step 3: Créer AccountDeletionListener**

```java
package com.dony.api.matching;

import com.dony.api.auth.events.AccountDeletionRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AccountDeletionListener {

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;

    public AccountDeletionListener(AnnouncementRepository announcementRepository,
                                   BidRepository bidRepository) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
    }

    @EventListener
    @Transactional
    public void onDeletionRequested(AccountDeletionRequestedEvent event) {
        announcementRepository.cancelOpenAnnouncementsByUserId(event.getUserId());
        bidRepository.cancelOpenSenderBidsByUserId(event.getUserId());
        bidRepository.cancelOpenTravelerBidsByUserId(event.getUserId());
    }
}
```

- [ ] **Step 4: Lancer les tests — vérifier qu'ils passent**

```bash
./mvnw test -Dtest=AccountDeletionListenerTest -q 2>&1 | tail -5
```

Expected : `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dony/api/matching/AccountDeletionListener.java \
        src/test/java/com/dony/api/matching/AccountDeletionListenerTest.java
git commit -m "feat: AccountDeletionListener archives announcements and bids on account deletion"
```

---

## Task 7 : UserService — refactorisation complète (TDD)

**Files:**
- Modify: `src/main/java/com/dony/api/auth/UserService.java`
- Create: `src/test/java/com/dony/api/auth/UserServiceDeleteAccountTest.java`

- [ ] **Step 1: Écrire tous les tests**

```java
package com.dony.api.auth;

import com.dony.api.auth.events.AccountDeletionRequestedEvent;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.kyc.KycRepository;
import com.dony.api.kyc.KycVerificationEntity;
import com.dony.api.payments.PaymentRepository;
import com.dony.api.payments.PaymentStatus;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — suppression de compte")
class UserServiceDeleteAccountTest {

    @Mock private UserRepository userRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private KycRepository kycRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private UserService userService;

    private static final String FIREBASE_UID = "uid-test-001";
    private static final UUID USER_ID = UUID.randomUUID();

    private UserEntity makeUser(UserStatus status) {
        UserEntity u = new UserEntity();
        setId(u, USER_ID);
        u.setFirebaseUid(FIREBASE_UID);
        u.setPhoneNumber("+33600000001");
        u.setEmail("test@example.com");
        u.setFirstName("Jean");
        u.setLastName("Dupont");
        u.setStatus(status);
        return u;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("requestDeletion")
    class RequestDeletion {

        @Test
        @DisplayName("ESCROW actif → 422")
        void escrowActive_throws422() {
            UserEntity user = makeUser(UserStatus.ACTIVE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(true);

            assertThatThrownBy(() -> userService.requestDeletion(FIREBASE_UID))
                .isInstanceOf(DonyBusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("succès → statut PENDING_DELETION, deletionRequestedAt set, event publié")
        void success_setsStatusAndPublishesEvent() {
            UserEntity user = makeUser(UserStatus.ACTIVE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(false);
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.requestDeletion(FIREBASE_UID);

            assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_DELETION);
            assertThat(user.getDeletionRequestedAt()).isNotNull();

            ArgumentCaptor<AccountDeletionRequestedEvent> captor =
                ArgumentCaptor.forClass(AccountDeletionRequestedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("déjà PENDING_DELETION → idempotent, event non re-publié")
        void alreadyPending_idempotent() {
            UserEntity user = makeUser(UserStatus.PENDING_DELETION);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            userService.requestDeletion(FIREBASE_UID);

            verify(eventPublisher, never()).publishEvent(any());
            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("reactivateAccount")
    class Reactivate {

        @Test
        @DisplayName("PENDING_DELETION → ACTIVE, deletionRequestedAt null, audit log")
        void success_reactivates() {
            UserEntity user = makeUser(UserStatus.PENDING_DELETION);
            user.setDeletionRequestedAt(Instant.now().minusSeconds(3600));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.reactivateAccount(FIREBASE_UID);

            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.getDeletionRequestedAt()).isNull();
            verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_DELETION_CANCELLED"), eq(USER_ID), any());
        }

        @Test
        @DisplayName("statut != PENDING_DELETION → 409")
        void notPending_throws409() {
            UserEntity user = makeUser(UserStatus.ACTIVE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> userService.reactivateAccount(FIREBASE_UID))
                .isInstanceOf(DonyBusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    @DisplayName("finalizeGdprDeletion")
    class FinalizeGdpr {

        @Test
        @DisplayName("pseudonymise + softDelete + KYC supprimé + audit log")
        void finalizes_pseudonymizesAndSoftDeletes() throws Exception {
            UserEntity user = makeUser(UserStatus.PENDING_DELETION);
            KycVerificationEntity kyc = new KycVerificationEntity();
            when(kycRepository.findByUserId(USER_ID)).thenReturn(Optional.of(kyc));

            com.google.firebase.auth.FirebaseAuth mockAuth =
                mock(com.google.firebase.auth.FirebaseAuth.class);
            try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
                staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);

                userService.finalizeGdprDeletion(user);
            }

            assertThat(user.getStatus()).isEqualTo(UserStatus.BANNED);
            assertThat(user.getDeletedAt()).isNotNull();
            assertThat(user.getEmail()).startsWith("deleted_");
            assertThat(user.getPhoneNumber()).isEqualTo("+00000000000");
            assertThat(user.getFirstName()).isEqualTo("Utilisateur");
            assertThat(user.getLastName()).isEqualTo("supprimé");
            assertThat(user.getFcmToken()).isNull();
            assertThat(kyc.getDeletedAt()).isNotNull();
            verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_GDPR_DELETION"), eq(USER_ID), any());
        }
    }
}
```

- [ ] **Step 2: Lancer les tests — vérifier qu'ils échouent**

```bash
./mvnw test -Dtest=UserServiceDeleteAccountTest -q 2>&1 | tail -10
```

Expected : FAIL (méthodes manquantes dans `UserService`)

- [ ] **Step 3: Ajouter hasActiveEscrowForUser à PaymentRepository**

Dans `PaymentRepository.java`, ajouter :

```java
@Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM PaymentEntity p " +
       "WHERE p.bidId IN " +
       "  (SELECT b.id FROM com.dony.api.matching.BidEntity b WHERE b.senderId = :userId " +
       "   UNION " +
       "   SELECT b2.id FROM com.dony.api.matching.BidEntity b2 " +
       "   JOIN com.dony.api.matching.AnnouncementEntity a ON b2.announcementId = a.id " +
       "   WHERE a.travelerId = :userId) " +
       "AND p.status = com.dony.api.payments.PaymentStatus.ESCROW")
boolean hasActiveEscrowForUser(@Param("userId") UUID userId);
```

- [ ] **Step 4: Refactoriser UserService**

Remplacer la méthode `deleteAccount` et ajouter `requestDeletion`, `reactivateAccount`, `finalizeGdprDeletion`. Supprimer les injections de `BidRepository` et `MatchingRepository` qui ne sont plus nécessaires.

Contenu complet de `UserService.java` :

```java
package com.dony.api.auth;

import com.dony.api.auth.dto.UpgradeToProRequest;
import com.dony.api.auth.events.AccountDeletionRequestedEvent;
import com.dony.api.auth.events.UserSuspendedEvent;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.kyc.KycRepository;
import com.dony.api.kyc.KycVerificationEntity;
import com.dony.api.payments.PaymentRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final int SUSPENSION_REFUSED_THRESHOLD = 2;

    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final KycRepository kycRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public UserService(UserRepository userRepository,
                       PaymentRepository paymentRepository,
                       KycRepository kycRepository,
                       AuditService auditService,
                       ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.kycRepository = kycRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    // Story 9.5 — Suspension automatique après trop de refus de colis
    @Transactional
    public void checkAndSuspendSender(UUID senderId) {
        userRepository.findById(senderId).ifPresent(sender -> {
            if (sender.getStatus() == UserStatus.SUSPENDED || sender.getStatus() == UserStatus.BANNED) {
                return;
            }
            if (sender.getRefusedCount() >= SUSPENSION_REFUSED_THRESHOLD) {
                sender.setStatus(UserStatus.SUSPENDED);
                userRepository.save(sender);

                auditService.log("USER", senderId, "USER_AUTO_SUSPENDED", senderId,
                        Map.of("reason", "refused_count_threshold",
                                "refusedCount", sender.getRefusedCount()));

                eventPublisher.publishEvent(new UserSuspendedEvent(
                        senderId,
                        sender.getPhoneNumber(),
                        sender.getEmail(),
                        "Suspension automatique suite à " + sender.getRefusedCount() + " colis refusés"
                ));

                log.warn("Sender {} auto-suspended after {} parcel refusals", senderId, sender.getRefusedCount());
            }
        });
    }

    // Story 9.8 — Droit à l'effacement RGPD — demande initiale (période de grâce 30 jours)
    @Transactional
    public void requestDeletion(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        if (user.getStatus() == UserStatus.PENDING_DELETION) {
            return;
        }

        if (paymentRepository.hasActiveEscrowForUser(user.getId())) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "active-transactions",
                    "Unprocessable", "Impossible — vous avez des transactions en cours");
        }

        user.setStatus(UserStatus.PENDING_DELETION);
        user.setDeletionRequestedAt(Instant.now());
        userRepository.save(user);

        eventPublisher.publishEvent(new AccountDeletionRequestedEvent(user.getId()));
        log.info("Account deletion requested for user {}", user.getId());
    }

    // Story 9.8 — Annulation de la demande de suppression (dans les 30 jours)
    @Transactional
    public void reactivateAccount(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        if (user.getStatus() != UserStatus.PENDING_DELETION) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "not-pending-deletion",
                    "Conflict", "Ce compte n'est pas en cours de suppression");
        }

        user.setStatus(UserStatus.ACTIVE);
        user.setDeletionRequestedAt(null);
        userRepository.save(user);

        auditService.log("USER", user.getId(), "USER_DELETION_CANCELLED", user.getId(), Map.of());
        log.info("Account deletion cancelled for user {}", user.getId());
    }

    // Story 9.8 — Finalisation RGPD à J+30 (appelé par le scheduler)
    @Transactional
    public void finalizeGdprDeletion(UserEntity user) {
        String uid = user.getId().toString();

        user.setEmail("deleted_" + uid + "@dony.app");
        user.setPhoneNumber("+00000000000");
        user.setFirstName("Utilisateur");
        user.setLastName("supprimé");
        user.setBirthDate(null);
        user.setCity(null);
        user.setFcmToken(null);
        user.setStatus(UserStatus.BANNED);
        user.softDelete();
        userRepository.save(user);

        Optional<KycVerificationEntity> kycOpt = kycRepository.findByUserId(user.getId());
        kycOpt.ifPresent(kyc -> {
            kyc.softDelete();
            kycRepository.save(kyc);
        });

        try {
            FirebaseAuth.getInstance().deleteUser(user.getFirebaseUid());
        } catch (FirebaseAuthException e) {
            log.warn("Could not delete Firebase user {}: {}", user.getFirebaseUid(), e.getMessage());
        }

        auditService.log("USER", user.getId(), "USER_GDPR_DELETION", user.getId(),
                Map.of("pseudonymized", true));
        log.info("GDPR deletion finalized for user {}", uid);
    }

    // Story 9.8 — Méthode conservée pour compatibilité avec AuthService (délègue à requestDeletion)
    @Transactional
    public void deleteAccount(String firebaseUid) {
        requestDeletion(firebaseUid);
    }

    // PR-1 — Upgrade to PRO account
    @Transactional
    public UserEntity upgradeToPro(UserEntity user, UpgradeToProRequest request) {
        UUID userId = user.getId();

        if (user.getStripeAccountId() != null && !user.getStripeAccountId().isBlank()) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT,
                    "stripe-account-exists",
                    "Stripe account already exists",
                    "Un compte Stripe Connect est déjà associé à ce compte"
            );
        }

        if (request.siret() != null && !request.siret().isBlank()) {
            if (!request.siret().matches("\\d{14}")) {
                throw new DonyBusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid-siret",
                        "Invalid SIRET",
                        "Le numéro SIRET doit contenir exactement 14 chiffres"
                );
            }
        }

        boolean alreadyPro = user.isProAccount();
        String auditAction = alreadyPro ? "USER_PRO_PROFILE_UPDATED" : "USER_UPGRADED_TO_PRO";

        user.setProAccount(true);
        user.setProCompanyName(request.companyName());
        user.setProSiret(request.siret());
        UserEntity saved = userRepository.save(user);

        auditService.log("USER", userId, auditAction, userId,
                Map.of("companyName", request.companyName() != null ? request.companyName() : "",
                        "siret", request.siret() != null ? request.siret() : ""));

        if (alreadyPro) {
            log.info("User {} PRO profile updated (companyName, siret)", userId);
        } else {
            log.info("User {} upgraded to PRO account", userId);
        }
        return saved;
    }

    // Story 9.5 — Admin unsuspend
    @Transactional
    public void unsuspendUser(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        auditService.log("USER", userId, "USER_UNSUSPENDED", userId, Map.of());
        log.info("User {} unsuspended by admin", userId);
    }
}
```

- [ ] **Step 5: Lancer les tests**

```bash
./mvnw test -Dtest=UserServiceDeleteAccountTest -q 2>&1 | tail -10
```

Expected : `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 6: Lancer tous les tests existants pour détecter les régressions**

```bash
./mvnw test -q 2>&1 | tail -15
```

Expected : tous verts (0 failures)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/dony/api/auth/UserService.java \
        src/main/java/com/dony/api/payments/PaymentRepository.java \
        src/test/java/com/dony/api/auth/UserServiceDeleteAccountTest.java
git commit -m "feat: refactor UserService with requestDeletion/reactivateAccount/finalizeGdprDeletion (RGPD)"
```

---

## Task 8 : AuthService + AuthController — endpoint reactivate

**Files:**
- Modify: `src/main/java/com/dony/api/auth/AuthService.java`
- Modify: `src/main/java/com/dony/api/auth/AuthController.java`

- [ ] **Step 1: Ajouter reactivateAccount dans AuthService**

Dans `AuthService.java`, ajouter la méthode après `deleteAccount` :

```java
@Transactional
public UserResponse reactivateAccount(String firebaseUid) {
    userService.reactivateAccount(firebaseUid);
    return getProfile(firebaseUid);
}
```

- [ ] **Step 2: Ajouter l'endpoint dans AuthController**

Dans `AuthController.java`, ajouter après le mapping `deleteAccount` :

```java
@PostMapping("/me/reactivate")
public ResponseEntity<UserResponse> reactivateAccount() {
    String firebaseUid = requireFirebaseUid();
    return ResponseEntity.ok(authService.reactivateAccount(firebaseUid));
}
```

- [ ] **Step 3: Compiler**

```bash
./mvnw compile -q
```

Expected : BUILD SUCCESS

- [ ] **Step 4: Lancer tous les tests**

```bash
./mvnw test -q 2>&1 | tail -10
```

Expected : 0 failures

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dony/api/auth/AuthService.java \
        src/main/java/com/dony/api/auth/AuthController.java
git commit -m "feat: add POST /auth/me/reactivate endpoint"
```

---

## Task 9 : AccountDeletionScheduler (TDD)

**Files:**
- Create: `src/main/java/com/dony/api/auth/AccountDeletionScheduler.java`
- Create: `src/test/java/com/dony/api/auth/AccountDeletionSchedulerTest.java`

- [ ] **Step 1: Écrire le test en premier**

```java
package com.dony.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountDeletionScheduler — tests unitaires")
class AccountDeletionSchedulerTest {

    @Mock private UserRepository userRepository;
    @Mock private UserService userService;

    @InjectMocks private AccountDeletionScheduler scheduler;

    private UserEntity makeUser(UUID id, Instant deletionRequestedAt) {
        UserEntity u = new UserEntity();
        setId(u, id);
        u.setFirebaseUid("uid-" + id);
        u.setStatus(UserStatus.PENDING_DELETION);
        u.setDeletionRequestedAt(deletionRequestedAt);
        return u;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("finalise uniquement les users avec deletionRequestedAt > 30j")
    void finalizesExpiredUsersOnly() {
        UUID expiredId = UUID.randomUUID();
        UUID recentId = UUID.randomUUID();
        Instant expired = Instant.now().minus(31, ChronoUnit.DAYS);
        UserEntity expiredUser = makeUser(expiredId, expired);

        when(userRepository.findByStatusAndDeletionRequestedAtBefore(
                eq(UserStatus.PENDING_DELETION), any(Instant.class)))
                .thenReturn(List.of(expiredUser));

        scheduler.finalizeExpiredDeletions();

        verify(userService).finalizeGdprDeletion(expiredUser);
    }

    @Test
    @DisplayName("aucun user expiré → finalizeGdprDeletion jamais appelé")
    void noExpiredUsers_doesNothing() {
        when(userRepository.findByStatusAndDeletionRequestedAtBefore(
                eq(UserStatus.PENDING_DELETION), any(Instant.class)))
                .thenReturn(List.of());

        scheduler.finalizeExpiredDeletions();

        verify(userService, never()).finalizeGdprDeletion(any());
    }

    @Test
    @DisplayName("cutoff passé au repository est bien now - 30 jours")
    void cutoffIs30DaysAgo() {
        when(userRepository.findByStatusAndDeletionRequestedAtBefore(
                eq(UserStatus.PENDING_DELETION), any(Instant.class)))
                .thenReturn(List.of());

        Instant before = Instant.now().minus(30, ChronoUnit.DAYS);
        scheduler.finalizeExpiredDeletions();
        Instant after = Instant.now().minus(30, ChronoUnit.DAYS);

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(userRepository).findByStatusAndDeletionRequestedAtBefore(
                eq(UserStatus.PENDING_DELETION), captor.capture());

        Instant cutoff = captor.getValue();
        assertThat(cutoff).isBetween(before.minusSeconds(5), after.plusSeconds(5));
    }
}
```

- [ ] **Step 2: Lancer les tests — vérifier qu'ils échouent**

```bash
./mvnw test -Dtest=AccountDeletionSchedulerTest -q 2>&1 | tail -5
```

Expected : FAIL — `AccountDeletionScheduler` n'existe pas

- [ ] **Step 3: Créer AccountDeletionScheduler**

```java
package com.dony.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class AccountDeletionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionScheduler.class);

    private final UserRepository userRepository;
    private final UserService userService;

    public AccountDeletionScheduler(UserRepository userRepository, UserService userService) {
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void finalizeExpiredDeletions() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        List<UserEntity> toDelete = userRepository
                .findByStatusAndDeletionRequestedAtBefore(UserStatus.PENDING_DELETION, cutoff);

        log.info("Account deletion scheduler: {} account(s) to finalize", toDelete.size());
        toDelete.forEach(userService::finalizeGdprDeletion);
    }
}
```

- [ ] **Step 4: Lancer les tests du scheduler**

```bash
./mvnw test -Dtest=AccountDeletionSchedulerTest -q 2>&1 | tail -5
```

Expected : `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Lancer tous les tests**

```bash
./mvnw test -q 2>&1 | tail -10
```

Expected : 0 failures

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/dony/api/auth/AccountDeletionScheduler.java \
        src/test/java/com/dony/api/auth/AccountDeletionSchedulerTest.java
git commit -m "feat: AccountDeletionScheduler finalizes GDPR deletion after 30-day grace period"
```

---

## Task 10 : Vérification finale — tous les tests

- [ ] **Step 1: Lancer la suite complète**

```bash
cd dony-back
./mvnw test 2>&1 | tail -20
```

Expected : `BUILD SUCCESS`, 0 failures, 0 errors

- [ ] **Step 2: Générer le rapport de couverture**

```bash
./mvnw test jacoco:report -q
```

Ouvrir `target/site/jacoco/index.html` et vérifier que la couverture globale est ≥ 90 %.

- [ ] **Step 3: Commit final si tout est vert**

```bash
git add -A
git commit -m "test: complete test suite for account deletion feature — all green"
```

---

## Récapitulatif des fichiers

| Fichier | Action |
|---------|--------|
| `V50__users_add_deletion_requested_at.sql` | Créé |
| `UserStatus.java` | PENDING_DELETION ajouté |
| `UserEntity.java` | deletionRequestedAt + getter/setter |
| `UserRepository.java` | findByStatusAndDeletionRequestedAtBefore |
| `PaymentRepository.java` | hasActiveEscrowForUser |
| `AccountDeletionRequestedEvent.java` | Créé |
| `UserService.java` | requestDeletion + reactivateAccount + finalizeGdprDeletion |
| `AuthService.java` | reactivateAccount délégué |
| `AuthController.java` | POST /auth/me/reactivate |
| `AccountDeletionScheduler.java` | Créé |
| `AnnouncementRepository.java` | cancelOpenAnnouncementsByUserId |
| `BidRepository.java` | cancelOpenSenderBidsByUserId + cancelOpenTravelerBidsByUserId |
| `AccountDeletionListener.java` | Créé |
| `UserServiceDeleteAccountTest.java` | Créé (6 tests) |
| `AccountDeletionSchedulerTest.java` | Créé (3 tests) |
| `AccountDeletionListenerTest.java` | Créé (3 tests) |
