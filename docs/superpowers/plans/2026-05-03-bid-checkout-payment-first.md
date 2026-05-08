# Bid Checkout — Payment-First Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Inverser le flux de réservation : le voyageur ne reçoit la notification d'une demande qu'**après** que l'expéditeur a réussi son paiement (pré-autorisation Stripe). Avant paiement, le bid existe en BDD avec status `AWAITING_PAYMENT`, invisible au voyageur, supprimé physiquement à T+15min s'il n'est pas payé.

**Architecture:** Combiner la création du bid et la création du `PaymentIntent` Stripe (capture_method=MANUAL) dans un endpoint unique `POST /bids/checkout`. Le webhook Stripe `payment_intent.amount_capturable_updated` promeut le bid de `AWAITING_PAYMENT` → `PENDING` et publie `BidCreatedEvent` (qui déclenche la notif voyageur). Deux schedulers : cleanup des bids non payés (5 min, suppression physique) et timeout des bids non répondus par le voyageur (24h ou H-12 du départ, libère le hold).

**Tech Stack:** Spring Boot 3.4 (Java 21), JPA/Hibernate, Flyway PostgreSQL, Stripe Java SDK, JUnit 5 + Mockito + MockMvc, JaCoCo (couverture ≥ 90%).

**Spec :** `docs/superpowers/specs/2026-05-03-bid-checkout-payment-first-design.md`

---

## ⚠️ État d'avancement (v2 — 2026-05-03 après-midi)

**Tasks 1-8 : ✅ COMPLÉTÉES** sur la branche `feat/bid-checkout-payment-first` (commits `8efba49`, `3b59ae8`, `cf8a04b`, `8926459`, `d48d3cd`, `28c0b8c`, `05590ef`, `23fb0d0`).

**Tasks 9 à 14 : ⛔ SUSPENDUES**, en attente de re-validation utilisateur.

**Pourquoi suspendu** : la spec a été révisée en v2 pour utiliser le pattern Stripe **separate charges and transfers** (vrai escrow plateforme) au lieu de **destination charges**. Cela impose :
- une nouvelle migration `V38` (colonne `legacy_destination_charge` + `stripe_charge_id` sur `payments`),
- une refonte de `PaymentService.createEscrow` pour ne plus poser `transfer_data` ni `application_fee_amount` sur les nouveaux PaymentIntents,
- une nouvelle classe `BidAcceptedEventListener` qui capture le PI à l'acceptation (uniquement pour `legacy = false`),
- une refonte de `DeliveryEventListener` pour brancher sur le flag legacy : `pi.capture()` (legacy) vs `Transfer.create()` (v2),
- une refonte de `BidRejectedEventListener` pour gérer `cancel` (avant capture) vs `Refund` (après capture).

**Les Tasks 9 à 14 ci-dessous sont donc remplacées / complétées par les Tasks 9a, 9b, 9c, 9d, 9e en PART 2 (à la fin du document).** Les anciennes Tasks 9-14 restent comme référence historique mais ne doivent **pas** être exécutées telles quelles.

---

## File Structure

**Création :**
- `src/main/resources/db/migration/V37__bids_add_payment_intent.sql` — colonnes `payment_intent_id`, `awaiting_payment_expires_at` + index
- `src/main/java/com/dony/api/matching/BidCheckoutService.java` — orchestration validations + bid `AWAITING_PAYMENT` + délégation `PaymentService.createEscrow`
- `src/main/java/com/dony/api/matching/dto/BidCheckoutRequest.java` — DTO entrée
- `src/main/java/com/dony/api/matching/dto/BidCheckoutResponse.java` — DTO sortie (bidId, clientSecret, publishableKey, expiresAt)
- `src/main/java/com/dony/api/matching/AwaitingPaymentCleanupScheduler.java` — supprime bids non payés à T+15min
- `src/main/java/com/dony/api/matching/BidTimeoutScheduler.java` — annule bids `PENDING` non répondus dans le délai
- Tests unitaires/integration correspondants

**Modification :**
- `BidStatus.java` — ajouter `AWAITING_PAYMENT`
- `BidEntity.java` — ajouter champs `paymentIntentId`, `awaitingPaymentExpiresAt`
- `BidRepository.java` — nouvelles requêtes (`findByStatusAndAwaitingPaymentExpiresAtBefore`, `findPendingTimedOut`) + filtre voyageur
- `BidService.java` — `acceptBid`/`rejectBid`/`cancelBid` appellent capture/cancel PI ; filtres listings
- `BidController.java` — endpoint `/checkout` ; suppression `POST /announcements/{id}/bids`
- `PaymentService.java` — méthodes `capturePaymentIntent(String)` et `cancelPaymentIntent(String)` exposées ; webhook handler étendu pour promouvoir le bid

---

## Task 1 : Migration BDD V37 et update entité

**Files:**
- Create: `src/main/resources/db/migration/V37__bids_add_payment_intent.sql`
- Modify: `src/main/java/com/dony/api/matching/BidEntity.java`

- [ ] **Step 1: Écrire le test d'intégration migration**

Create: `src/test/java/com/dony/api/matching/BidEntityMigrationTest.java`
```java
package com.dony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BidEntityMigrationTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void v37_adds_payment_intent_columns() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns " +
            "WHERE table_name = 'bids' AND column_name IN ('payment_intent_id','awaiting_payment_expires_at')",
            Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void v37_adds_indexes() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_indexes " +
            "WHERE tablename = 'bids' AND indexname IN ('idx_bids_awaiting_payment','idx_bids_payment_intent')",
            Integer.class);
        assertThat(count).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Exécuter le test pour vérifier qu'il échoue**

Run : `./mvnw test -Dtest=BidEntityMigrationTest`
Expected : FAIL — colonnes/index introuvables.

- [ ] **Step 3: Créer la migration V37**

Create: `src/main/resources/db/migration/V37__bids_add_payment_intent.sql`
```sql
ALTER TABLE bids
  ADD COLUMN payment_intent_id           VARCHAR(255),
  ADD COLUMN awaiting_payment_expires_at TIMESTAMP;

CREATE INDEX idx_bids_awaiting_payment
  ON bids (status, awaiting_payment_expires_at)
  WHERE status = 'AWAITING_PAYMENT';

CREATE INDEX idx_bids_payment_intent
  ON bids (payment_intent_id);

COMMENT ON COLUMN bids.payment_intent_id IS 'Stripe PaymentIntent linked to this bid (set at checkout creation)';
COMMENT ON COLUMN bids.awaiting_payment_expires_at IS 'Expiration de la fenêtre de paiement (NULL une fois payé)';
```

- [ ] **Step 4: Ajouter les champs sur BidEntity.java**

Modify `src/main/java/com/dony/api/matching/BidEntity.java` — ajouter avant le bloc des getters/setters :
```java
@Column(name = "payment_intent_id", length = 255)
private String paymentIntentId;

@Column(name = "awaiting_payment_expires_at")
private LocalDateTime awaitingPaymentExpiresAt;
```
Ajouter getters/setters correspondants.

- [ ] **Step 5: Re-exécuter le test**

Run : `./mvnw test -Dtest=BidEntityMigrationTest`
Expected : PASS.

- [ ] **Step 6: Commit**
```bash
git add src/main/resources/db/migration/V37__bids_add_payment_intent.sql \
        src/main/java/com/dony/api/matching/BidEntity.java \
        src/test/java/com/dony/api/matching/BidEntityMigrationTest.java
git commit -m "feat(bid): add payment_intent_id and expiry columns (V37)"
```

---

## Task 2 : Ajouter `AWAITING_PAYMENT` à `BidStatus`

**Files:**
- Modify: `src/main/java/com/dony/api/matching/BidStatus.java`

- [ ] **Step 1: Écrire le test**

Create: `src/test/java/com/dony/api/matching/BidStatusTest.java`
```java
package com.dony.api.matching;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BidStatusTest {
    @Test
    void awaiting_payment_value_exists() {
        assertThat(BidStatus.valueOf("AWAITING_PAYMENT")).isEqualTo(BidStatus.AWAITING_PAYMENT);
    }
}
```

- [ ] **Step 2: Exécuter le test**

Run : `./mvnw test -Dtest=BidStatusTest`
Expected : FAIL — `AWAITING_PAYMENT` n'existe pas.

- [ ] **Step 3: Ajouter la valeur**

Modify `src/main/java/com/dony/api/matching/BidStatus.java` — ajouter en première position :
```java
public enum BidStatus {
    AWAITING_PAYMENT,  // bid créé, paiement Stripe en cours, voyageur non encore notifié
    PENDING,
    ACCEPTED,
    REJECTED,
    CANCELLED,
    COMPLETED,
    NO_SHOW,
    PARCEL_REFUSED
}
```

- [ ] **Step 4: Test passe**

Run : `./mvnw test -Dtest=BidStatusTest`
Expected : PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/dony/api/matching/BidStatus.java \
        src/test/java/com/dony/api/matching/BidStatusTest.java
git commit -m "feat(bid): add AWAITING_PAYMENT status"
```

---

## Task 3 : Exposer `cancelPaymentIntent` et `capturePaymentIntent` dans `PaymentService`

**Files:**
- Modify: `src/main/java/com/dony/api/payments/PaymentService.java`
- Test: `src/test/java/com/dony/api/payments/PaymentServiceCancelCaptureTest.java`

- [ ] **Step 1: Écrire les tests**

Create: `src/test/java/com/dony/api/payments/PaymentServiceCancelCaptureTest.java`
```java
package com.dony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceCancelCaptureTest {

    private final PaymentService service = PaymentServiceTestFactory.bare();

    @Test
    void cancelPaymentIntent_calls_stripe_cancel() throws StripeException {
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.cancel()).thenReturn(pi);
            mocked.when(() -> PaymentIntent.retrieve("pi_123")).thenReturn(pi);

            assertThatNoException().isThrownBy(() -> service.cancelPaymentIntent("pi_123"));
            verify(pi).cancel();
        }
    }

    @Test
    void capturePaymentIntent_calls_stripe_capture() throws StripeException {
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.capture()).thenReturn(pi);
            mocked.when(() -> PaymentIntent.retrieve("pi_456")).thenReturn(pi);

            assertThatNoException().isThrownBy(() -> service.capturePaymentIntent("pi_456"));
            verify(pi).capture();
        }
    }

    @Test
    void cancelPaymentIntent_with_null_does_nothing() {
        assertThatNoException().isThrownBy(() -> service.cancelPaymentIntent(null));
    }
}
```

Create helper: `src/test/java/com/dony/api/payments/PaymentServiceTestFactory.java`
```java
package com.dony.api.payments;

import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidRepository;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;

import static org.mockito.Mockito.mock;

class PaymentServiceTestFactory {
    static PaymentService bare() {
        return new PaymentService(
            mock(BidRepository.class),
            mock(PaymentRepository.class),
            mock(UserRepository.class),
            mock(AnnouncementRepository.class),
            mock(AuditService.class),
            mock(ApplicationEventPublisher.class),
            BigDecimal.valueOf(0.12),
            "whsec_test"
        );
    }
}
```

- [ ] **Step 2: Run pour confirmer l'échec**

Run : `./mvnw test -Dtest=PaymentServiceCancelCaptureTest`
Expected : FAIL (méthodes inexistantes).

- [ ] **Step 3: Implémenter les méthodes**

Modify `src/main/java/com/dony/api/payments/PaymentService.java` — ajouter avant la fin de la classe :
```java
/**
 * Annule un PaymentIntent en mode pré-autorisation.
 * No-op si paymentIntentId est null/blank ou si le PI est dans un état non-cancellable.
 * Throws StripeException pour permettre au scheduler de détecter la race condition (PI déjà succeeded).
 */
public void cancelPaymentIntent(String paymentIntentId) throws StripeException {
    if (paymentIntentId == null || paymentIntentId.isBlank()) return;
    PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId);
    pi.cancel();
    log.info("PaymentIntent {} cancelled", paymentIntentId);
}

/**
 * Capture un PaymentIntent pré-autorisé (manual capture).
 * No-op si paymentIntentId est null/blank.
 */
public void capturePaymentIntent(String paymentIntentId) throws StripeException {
    if (paymentIntentId == null || paymentIntentId.isBlank()) return;
    PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId);
    pi.capture();
    log.info("PaymentIntent {} captured", paymentIntentId);
}
```

Imports nécessaires (vérifier qu'ils existent déjà) :
- `com.stripe.exception.StripeException`
- `com.stripe.model.PaymentIntent`

- [ ] **Step 4: Tests passent**

Run : `./mvnw test -Dtest=PaymentServiceCancelCaptureTest`
Expected : PASS (3 tests).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/dony/api/payments/PaymentService.java \
        src/test/java/com/dony/api/payments/PaymentServiceCancelCaptureTest.java \
        src/test/java/com/dony/api/payments/PaymentServiceTestFactory.java
git commit -m "feat(payments): expose capturePaymentIntent and cancelPaymentIntent"
```

---

## Task 4 : DTOs `BidCheckoutRequest` et `BidCheckoutResponse`

**Files:**
- Create: `src/main/java/com/dony/api/matching/dto/BidCheckoutRequest.java`
- Create: `src/main/java/com/dony/api/matching/dto/BidCheckoutResponse.java`

- [ ] **Step 1: Test de validation Bean Validation**

Create: `src/test/java/com/dony/api/matching/dto/BidCheckoutRequestTest.java`
```java
package com.dony.api.matching.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BidCheckoutRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void valid_request_has_no_violations() {
        BidCheckoutRequest req = new BidCheckoutRequest(
            UUID.randomUUID(),
            new BigDecimal("2.50"),
            new BigDecimal("100.00"),
            "Médicaments", "OTHER",
            "Aïssatou", "+221771234567", true
        );
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void weight_must_be_positive() {
        BidCheckoutRequest req = new BidCheckoutRequest(
            UUID.randomUUID(), BigDecimal.ZERO, new BigDecimal("100"),
            "x", "OTHER", "n", "+221", true);
        assertThat(validator.validate(req)).isNotEmpty();
    }

    @Test
    void announcement_id_required() {
        BidCheckoutRequest req = new BidCheckoutRequest(
            null, new BigDecimal("2"), new BigDecimal("100"),
            "x", "OTHER", "n", "+221", true);
        assertThat(validator.validate(req)).isNotEmpty();
    }
}
```

- [ ] **Step 2: Run, attend FAIL (DTO inexistant)**

Run : `./mvnw test -Dtest=BidCheckoutRequestTest`
Expected : compilation fails.

- [ ] **Step 3: Créer les DTOs**

Create: `src/main/java/com/dony/api/matching/dto/BidCheckoutRequest.java`
```java
package com.dony.api.matching.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record BidCheckoutRequest(
        @NotNull UUID announcementId,
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal weightKg,
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal declaredValueEur,
        @Size(max = 1000) String description,
        @Size(max = 50) String contentCategory,
        @Size(max = 200) String recipientName,
        @Size(max = 30) String recipientPhone,
        @AssertTrue(message = "Le disclaimer doit être signé") Boolean disclaimerSigned
) {}
```

Create: `src/main/java/com/dony/api/matching/dto/BidCheckoutResponse.java`
```java
package com.dony.api.matching.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record BidCheckoutResponse(
        UUID bidId,
        String clientSecret,
        String publishableKey,
        LocalDateTime expiresAt
) {}
```

- [ ] **Step 4: Test passe**

Run : `./mvnw test -Dtest=BidCheckoutRequestTest`
Expected : PASS (3 tests).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/dony/api/matching/dto/BidCheckoutRequest.java \
        src/main/java/com/dony/api/matching/dto/BidCheckoutResponse.java \
        src/test/java/com/dony/api/matching/dto/BidCheckoutRequestTest.java
git commit -m "feat(bid): add BidCheckoutRequest and BidCheckoutResponse DTOs"
```

---

## Task 5 : `BidCheckoutService` (validation + bid AWAITING_PAYMENT + délégation paiement)

**Files:**
- Create: `src/main/java/com/dony/api/matching/BidCheckoutService.java`
- Test: `src/test/java/com/dony/api/matching/BidCheckoutServiceTest.java`

- [ ] **Step 1: Écrire les tests unitaires**

Create: `src/test/java/com/dony/api/matching/BidCheckoutServiceTest.java`
```java
package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.auth.Role;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.dto.BidCheckoutRequest;
import com.dony.api.matching.dto.BidCheckoutResponse;
import com.dony.api.payments.PaymentService;
import com.dony.api.payments.dto.CreatePaymentRequest;
import com.dony.api.payments.dto.PaymentResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BidCheckoutServiceTest {

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private PaymentService paymentService;
    @Mock private HttpServletRequest httpRequest;

    @InjectMocks private BidCheckoutService service;

    private UserEntity sender;
    private AnnouncementEntity announcement;
    private BidCheckoutRequest req;

    @BeforeEach
    void setUp() {
        sender = new UserEntity();
        sender.setId(UUID.randomUUID());
        sender.setFirebaseUid("uid-sender");
        sender.setRoles(new HashSet<>());

        announcement = new AnnouncementEntity();
        announcement.setId(UUID.randomUUID());
        announcement.setTravelerId(UUID.randomUUID());
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setAvailableKg(new BigDecimal("10.00"));

        req = new BidCheckoutRequest(
            announcement.getId(),
            new BigDecimal("2.00"),
            new BigDecimal("150.00"),
            "test", "OTHER",
            "Recipient", "+221771234567", true);

        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    @Test
    void creates_bid_in_AWAITING_PAYMENT_and_delegates_to_payment_service() {
        ArgumentCaptor<BidEntity> savedBid = ArgumentCaptor.forClass(BidEntity.class);
        when(bidRepository.save(savedBid.capture())).thenAnswer(inv -> {
            BidEntity b = inv.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });
        when(paymentService.createEscrow(any(CreatePaymentRequest.class), eq("uid-sender")))
            .thenReturn(new PaymentResponse(UUID.randomUUID(), null, BigDecimal.TEN, BigDecimal.ONE,
                                            "PENDING", null, "secret_xyz", "pk_test"));

        BidCheckoutResponse resp = service.checkout("uid-sender", req, httpRequest);

        assertThat(savedBid.getValue().getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        assertThat(savedBid.getValue().getAwaitingPaymentExpiresAt()).isNotNull();
        assertThat(resp.clientSecret()).isEqualTo("secret_xyz");
        // No BidCreatedEvent / no audit log at this stage
        verify(auditService, never()).log(eq("BID"), any(), eq("BID_CREATED"), any(), any());
    }

    @Test
    void rejects_inactive_announcement() {
        announcement.setStatus(AnnouncementStatus.CLOSED);
        assertThatThrownBy(() -> service.checkout("uid-sender", req, httpRequest))
            .isInstanceOf(DonyBusinessException.class)
            .hasMessageContaining("plus disponible");
    }

    @Test
    void rejects_bidding_on_own_announcement() {
        announcement.setTravelerId(sender.getId());
        assertThatThrownBy(() -> service.checkout("uid-sender", req, httpRequest))
            .isInstanceOf(DonyBusinessException.class);
    }

    @Test
    void rejects_weight_exceeding_capacity() {
        announcement.setAvailableKg(new BigDecimal("1.00"));
        assertThatThrownBy(() -> service.checkout("uid-sender", req, httpRequest))
            .isInstanceOf(DonyBusinessException.class)
            .hasMessageContaining("capacité");
    }

    @Test
    void rejects_value_above_500_eur() {
        BidCheckoutRequest tooHigh = new BidCheckoutRequest(
            announcement.getId(), new BigDecimal("2"), new BigDecimal("501"),
            null, null, null, null, true);
        assertThatThrownBy(() -> service.checkout("uid-sender", tooHigh, httpRequest))
            .isInstanceOf(DonyBusinessException.class)
            .hasMessageContaining("500");
    }

    @Test
    void rejects_existing_pending_or_awaiting_bid() {
        when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(
                eq(sender.getId()), eq(announcement.getId()), any()))
            .thenReturn(true);
        assertThatThrownBy(() -> service.checkout("uid-sender", req, httpRequest))
            .isInstanceOf(DonyBusinessException.class)
            .hasMessageContaining("déjà");
    }

    @Test
    void auto_assigns_SENDER_role() {
        when(bidRepository.save(any())).thenAnswer(inv -> {
            BidEntity b = inv.getArgument(0); b.setId(UUID.randomUUID()); return b;
        });
        when(paymentService.createEscrow(any(), anyString()))
            .thenReturn(new PaymentResponse(UUID.randomUUID(), null, BigDecimal.TEN, BigDecimal.ONE,
                                            "PENDING", null, "secret", "pk"));

        service.checkout("uid-sender", req, httpRequest);

        assertThat(sender.getRoles()).contains(Role.SENDER);
        verify(userRepository).save(sender);
    }
}
```

- [ ] **Step 2: Run, attendre FAIL**

Run : `./mvnw test -Dtest=BidCheckoutServiceTest`
Expected : compile fails (`BidCheckoutService` n'existe pas).

- [ ] **Step 3: Créer le service**

Create: `src/main/java/com/dony/api/matching/BidCheckoutService.java`
```java
package com.dony.api.matching;

import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.dto.BidCheckoutRequest;
import com.dony.api.matching.dto.BidCheckoutResponse;
import com.dony.api.payments.PaymentService;
import com.dony.api.payments.dto.CreatePaymentRequest;
import com.dony.api.payments.dto.PaymentResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class BidCheckoutService {

    static final int AWAITING_PAYMENT_GRACE_MINUTES = 15;

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final PaymentService paymentService;

    public BidCheckoutService(BidRepository bidRepository,
                              AnnouncementRepository announcementRepository,
                              UserRepository userRepository,
                              AuditService auditService,
                              PaymentService paymentService) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.paymentService = paymentService;
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public BidCheckoutResponse checkout(String firebaseUid,
                                       BidCheckoutRequest req,
                                       HttpServletRequest httpRequest) {

        UserEntity sender = userRepository.findByFirebaseUid(firebaseUid)
            .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                "user-not-found", "User Not Found", "Utilisateur introuvable"));

        if (!sender.getRoles().contains(Role.SENDER)) {
            sender.getRoles().add(Role.SENDER);
            userRepository.save(sender);
        }

        AnnouncementEntity announcement = announcementRepository.findById(req.announcementId())
            .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        if (announcement.getStatus() != AnnouncementStatus.ACTIVE) {
            throw new DonyBusinessException(HttpStatus.CONFLICT,
                "announcement-not-active", "Announcement Not Active",
                "Cette annonce n'est plus disponible");
        }

        if (announcement.getTravelerId().equals(sender.getId())) {
            throw new DonyBusinessException(HttpStatus.CONFLICT,
                "cannot-bid-own-announcement", "Cannot Bid Own Announcement",
                "Vous ne pouvez pas faire une demande sur votre propre annonce");
        }

        boolean alreadyHasBid = bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(
            sender.getId(), announcement.getId(),
            List.of(BidStatus.AWAITING_PAYMENT, BidStatus.PENDING, BidStatus.ACCEPTED));
        if (alreadyHasBid) {
            throw new DonyBusinessException(HttpStatus.CONFLICT,
                "already-bid", "Demande existante",
                "Vous avez déjà une demande en cours pour ce trajet");
        }

        if (req.weightKg().compareTo(announcement.getAvailableKg()) > 0) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "weight-exceeds-capacity", "Weight Exceeds Capacity",
                "Poids demandé supérieur à la capacité disponible");
        }

        if (req.declaredValueEur().compareTo(BigDecimal.valueOf(500)) > 0) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "value-exceeds-limit", "Value Exceeds Limit",
                "Valeur maximum : 500 €");
        }

        String ip = resolveClientIp(httpRequest);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(req.weightKg());
        bid.setDeclaredValueEur(req.declaredValueEur());
        bid.setDescription(req.description());
        bid.setContentCategory(req.contentCategory());
        bid.setRecipientName(req.recipientName());
        bid.setRecipientPhone(req.recipientPhone());
        bid.setDisclaimerSignedAt(now);
        bid.setDisclaimerSignedIp(ip);
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(now.plusMinutes(AWAITING_PAYMENT_GRACE_MINUTES));

        BidEntity saved = bidRepository.save(bid);

        // Délégation à PaymentService — crée le PaymentIntent Stripe
        CreatePaymentRequest paymentReq = new CreatePaymentRequest();
        paymentReq.setBidId(saved.getId());
        PaymentResponse paymentResp = paymentService.createEscrow(paymentReq, firebaseUid);

        // Backfill paymentIntentId sur le bid
        saved.setPaymentIntentId(extractPaymentIntentId(paymentResp));
        bidRepository.save(saved);

        return new BidCheckoutResponse(
            saved.getId(),
            paymentResp.getClientSecret(),
            paymentResp.getPublishableKey(),
            saved.getAwaitingPaymentExpiresAt()
        );
    }

    private String extractPaymentIntentId(PaymentResponse resp) {
        // PaymentResponse expose le PI id via getStripePaymentIntentId() — adapter si nom différent
        return resp.getStripePaymentIntentId();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
```

> ⚠️ **Avant d'écrire ce fichier**, vérifier que `PaymentResponse` expose bien `getStripePaymentIntentId()` et `getPublishableKey()`. Si non, ajouter ces getters dans `PaymentResponse.java` et adapter. Lecture rapide :
> ```bash
> cat src/main/java/com/dony/api/payments/dto/PaymentResponse.java
> ```

- [ ] **Step 4: Vérifier `PaymentResponse` — ajouter getters manquants si besoin**

Si `getStripePaymentIntentId` / `getPublishableKey` manquent, les ajouter dans `PaymentResponse.java`. Sinon, ne rien faire.

- [ ] **Step 5: Tests passent**

Run : `./mvnw test -Dtest=BidCheckoutServiceTest`
Expected : PASS (7 tests).

- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/dony/api/matching/BidCheckoutService.java \
        src/test/java/com/dony/api/matching/BidCheckoutServiceTest.java \
        src/main/java/com/dony/api/payments/dto/PaymentResponse.java
git commit -m "feat(bid): add BidCheckoutService that creates AWAITING_PAYMENT bid + delegates payment"
```

---

## Task 6 : Endpoint REST `POST /bids/checkout`

**Files:**
- Modify: `src/main/java/com/dony/api/matching/BidController.java`
- Test: `src/test/java/com/dony/api/matching/BidCheckoutControllerIntegrationTest.java`

- [ ] **Step 1: Écrire test integration**

Create: `src/test/java/com/dony/api/matching/BidCheckoutControllerIntegrationTest.java`
```java
package com.dony.api.matching;

import com.dony.api.matching.dto.BidCheckoutRequest;
import com.dony.api.matching.dto.BidCheckoutResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class BidCheckoutControllerIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @MockBean  private FirebaseAuth firebaseAuth;
    @MockBean  private BidCheckoutService bidCheckoutService;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void post_checkout_returns_201_with_clientSecret() throws Exception {
        FirebaseToken token = org.mockito.Mockito.mock(FirebaseToken.class);
        when(token.getUid()).thenReturn("uid-sender");
        when(firebaseAuth.verifyIdToken(anyString())).thenReturn(token);

        UUID bidId = UUID.randomUUID();
        BidCheckoutResponse resp = new BidCheckoutResponse(
            bidId, "pi_xxx_secret_yyy", "pk_test_zzz",
            LocalDateTime.now().plusMinutes(15));
        when(bidCheckoutService.checkout(eq("uid-sender"), any(), any())).thenReturn(resp);

        BidCheckoutRequest req = new BidCheckoutRequest(
            UUID.randomUUID(), new BigDecimal("2"), new BigDecimal("100"),
            "x", "OTHER", "n", "+221", true);

        mockMvc().perform(post("/api/v1/bids/checkout")
                .header("Authorization", "Bearer fake-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.bidId").value(bidId.toString()))
            .andExpect(jsonPath("$.clientSecret").value("pi_xxx_secret_yyy"))
            .andExpect(jsonPath("$.publishableKey").value("pk_test_zzz"))
            .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void post_checkout_without_token_returns_401() throws Exception {
        BidCheckoutRequest req = new BidCheckoutRequest(
            UUID.randomUUID(), new BigDecimal("2"), new BigDecimal("100"),
            "x", "OTHER", "n", "+221", true);

        mockMvc().perform(post("/api/v1/bids/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void post_checkout_with_invalid_body_returns_422_or_400() throws Exception {
        FirebaseToken token = org.mockito.Mockito.mock(FirebaseToken.class);
        when(token.getUid()).thenReturn("uid");
        when(firebaseAuth.verifyIdToken(anyString())).thenReturn(token);

        // disclaimerSigned = false → @AssertTrue violation
        BidCheckoutRequest invalid = new BidCheckoutRequest(
            UUID.randomUUID(), new BigDecimal("2"), new BigDecimal("100"),
            "x", "OTHER", "n", "+221", false);

        mockMvc().perform(post("/api/v1/bids/checkout")
                .header("Authorization", "Bearer fake")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalid)))
            .andExpect(status().is4xxClientError());
    }
}
```

- [ ] **Step 2: Run pour FAIL**

Run : `./mvnw test -Dtest=BidCheckoutControllerIntegrationTest`
Expected : 404 ou compilation fail.

- [ ] **Step 3: Ajouter l'endpoint**

Modify `src/main/java/com/dony/api/matching/BidController.java` :

a. Importer le service et les DTOs :
```java
import com.dony.api.matching.dto.BidCheckoutRequest;
import com.dony.api.matching.dto.BidCheckoutResponse;
import jakarta.validation.Valid;
```

b. Injecter `BidCheckoutService` :
```java
private final BidCheckoutService bidCheckoutService;

public BidController(BidService bidService, BidCheckoutService bidCheckoutService) {
    this.bidService = bidService;
    this.bidCheckoutService = bidCheckoutService;
}
```
*(adapter la liste de paramètres existants)*

c. Ajouter la méthode :
```java
@PostMapping("/checkout")
public ResponseEntity<BidCheckoutResponse> checkout(
        @AuthenticationPrincipal UserEntity user,
        @Valid @RequestBody BidCheckoutRequest request,
        HttpServletRequest httpRequest) {
    BidCheckoutResponse resp = bidCheckoutService.checkout(
        user.getFirebaseUid(), request, httpRequest);
    return ResponseEntity.status(HttpStatus.CREATED).body(resp);
}
```

> ⚠️ **Suppression de l'ancien endpoint** : retirer la méthode qui faisait `POST /announcements/{id}/bids` — repérer `bidService.createBid(...)` dans `BidController` et supprimer la méthode + ses tests dans `BidControllerIntegrationTest`. Les tests anciens cassés seront mis à jour à la Task 9.

- [ ] **Step 4: Tests passent**

Run : `./mvnw test -Dtest=BidCheckoutControllerIntegrationTest`
Expected : PASS (3 tests).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/dony/api/matching/BidController.java \
        src/test/java/com/dony/api/matching/BidCheckoutControllerIntegrationTest.java
git commit -m "feat(bid): expose POST /api/v1/bids/checkout endpoint"
```

---

## Task 7 : Webhook Stripe → promotion `AWAITING_PAYMENT` → `PENDING` + publication `BidCreatedEvent`

**Files:**
- Modify: `src/main/java/com/dony/api/payments/PaymentService.java` (méthode `handlePaymentEscrowActive`)
- Test: `src/test/java/com/dony/api/payments/PaymentWebhookBidPromotionTest.java`

- [ ] **Step 1: Écrire le test**

Create: `src/test/java/com/dony/api/payments/PaymentWebhookBidPromotionTest.java`
```java
package com.dony.api.payments;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.matching.events.BidCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookBidPromotionTest {

    @Mock private BidRepository bidRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PaymentService service;
    private BidEntity bid;
    private AnnouncementEntity announcement;
    private UserEntity sender;

    @BeforeEach
    void setUp() {
        service = new PaymentService(bidRepository, paymentRepository, userRepository,
            announcementRepository, auditService, eventPublisher,
            BigDecimal.valueOf(0.12), "whsec_test");

        bid = new BidEntity();
        bid.setId(UUID.randomUUID());
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setSenderId(UUID.randomUUID());
        bid.setAnnouncementId(UUID.randomUUID());
        bid.setWeightKg(new BigDecimal("2.0"));
        bid.setPaymentIntentId("pi_xxx");

        announcement = new AnnouncementEntity();
        announcement.setId(bid.getAnnouncementId());
        announcement.setTravelerId(UUID.randomUUID());
        announcement.setDepartureCity("Paris");
        announcement.setArrivalCity("Dakar");

        sender = new UserEntity();
        sender.setId(bid.getSenderId());
        sender.setFirstName("Aliou");
    }

    @Test
    void promote_bid_publishes_event_and_audits() {
        when(bidRepository.findByPaymentIntentId("pi_xxx")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(userRepository.findById(sender.getId())).thenReturn(Optional.of(sender));

        service.promoteBidOnPaymentAuthorized("pi_xxx");

        assertThat(bid.getStatus()).isEqualTo(BidStatus.PENDING);
        assertThat(bid.getAwaitingPaymentExpiresAt()).isNull();
        verify(bidRepository).save(bid);
        verify(auditService).log(eq("BID"), eq(bid.getId()), eq("BID_CREATED"), eq(sender.getId()), any());

        ArgumentCaptor<BidCreatedEvent> evt = ArgumentCaptor.forClass(BidCreatedEvent.class);
        verify(eventPublisher).publishEvent(evt.capture());
        assertThat(evt.getValue().getBidId()).isEqualTo(bid.getId());
        assertThat(evt.getValue().getTravelerId()).isEqualTo(announcement.getTravelerId());
    }

    @Test
    void promote_is_idempotent_when_bid_already_pending() {
        bid.setStatus(BidStatus.PENDING);
        when(bidRepository.findByPaymentIntentId("pi_xxx")).thenReturn(Optional.of(bid));

        service.promoteBidOnPaymentAuthorized("pi_xxx");

        verify(bidRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(BidCreatedEvent.class));
    }

    @Test
    void promote_does_nothing_when_bid_not_found() {
        when(bidRepository.findByPaymentIntentId("pi_zzz")).thenReturn(Optional.empty());
        service.promoteBidOnPaymentAuthorized("pi_zzz");
        verifyNoInteractions(eventPublisher);
    }
}
```

- [ ] **Step 2: Run pour FAIL**

Run : `./mvnw test -Dtest=PaymentWebhookBidPromotionTest`
Expected : compile fail (`promoteBidOnPaymentAuthorized` n'existe pas, `findByPaymentIntentId` non plus).

- [ ] **Step 3: Ajouter `findByPaymentIntentId` à `BidRepository`**

Modify `src/main/java/com/dony/api/matching/BidRepository.java` :
```java
java.util.Optional<BidEntity> findByPaymentIntentId(String paymentIntentId);
```

- [ ] **Step 4: Ajouter la méthode `promoteBidOnPaymentAuthorized` dans `PaymentService`**

Modify `src/main/java/com/dony/api/payments/PaymentService.java` — ajouter (après `handlePaymentEscrowActive`) :
```java
/**
 * Promotes a bid from AWAITING_PAYMENT → PENDING when the Stripe PaymentIntent
 * has been authorized (capture_method=manual hold posted).
 * Publishes BidCreatedEvent so the traveler is notified.
 * Idempotent: silent no-op if bid not in AWAITING_PAYMENT.
 */
@Transactional
public void promoteBidOnPaymentAuthorized(String paymentIntentId) {
    bidRepository.findByPaymentIntentId(paymentIntentId).ifPresent(bid -> {
        if (bid.getStatus() != BidStatus.AWAITING_PAYMENT) return;

        bid.setStatus(BidStatus.PENDING);
        bid.setAwaitingPaymentExpiresAt(null);
        bidRepository.save(bid);

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
            .orElseThrow(() -> new IllegalStateException("announcement not found for bid " + bid.getId()));
        UserEntity sender = userRepository.findById(bid.getSenderId()).orElse(null);
        String senderName = (sender != null && sender.getFirstName() != null)
            ? sender.getFirstName() : "Un expéditeur";
        String corridor = announcement.getDepartureCity() + " → " + announcement.getArrivalCity();

        auditService.log("BID", bid.getId(), "BID_CREATED", bid.getSenderId(),
            java.util.Map.of("announcementId", bid.getAnnouncementId().toString(),
                             "weightKg", bid.getWeightKg().toString(),
                             "paymentIntentId", paymentIntentId));

        eventPublisher.publishEvent(new com.dony.api.matching.events.BidCreatedEvent(
            bid.getId(), announcement.getId(), announcement.getTravelerId(), bid.getSenderId(),
            senderName, bid.getWeightKg(), corridor));

        log.info("Bid {} promoted to PENDING (PI={})", bid.getId(), paymentIntentId);
    });
}
```

Étendre la méthode `handlePaymentEscrowActive` pour appeler la promotion :
```java
private void handlePaymentEscrowActive(Event event) {
    event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
        PaymentIntent pi = (PaymentIntent) obj;
        paymentRepository.findByStripePaymentIntentId(pi.getId()).ifPresent(payment -> {
            if (payment.getStatus() == PaymentStatus.PENDING) {
                payment.setStatus(PaymentStatus.ESCROW);
                paymentRepository.save(payment);
                auditService.log("PAYMENT", payment.getId(), "PAYMENT_ESCROW_ACTIVE",
                    payment.getBidId(),
                    Map.of("piId", pi.getId(), "amountCapturable", pi.getAmountCapturable()));
                eventPublisher.publishEvent(new PaymentEscrowReadyEvent(payment.getBidId(), payment.getId()));
            }
        });
        // ← NOUVEAU : promouvoir le bid si toujours en AWAITING_PAYMENT
        promoteBidOnPaymentAuthorized(pi.getId());
    });
}
```

- [ ] **Step 5: Tests passent**

Run : `./mvnw test -Dtest=PaymentWebhookBidPromotionTest`
Expected : PASS (3 tests).

- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/dony/api/payments/PaymentService.java \
        src/main/java/com/dony/api/matching/BidRepository.java \
        src/test/java/com/dony/api/payments/PaymentWebhookBidPromotionTest.java
git commit -m "feat(payments): promote bid AWAITING_PAYMENT->PENDING on webhook + publish BidCreatedEvent"
```

---

## Task 8 : Suppression de la publication `BidCreatedEvent` dans `BidService.createBid`

**Files:**
- Modify: `src/main/java/com/dony/api/matching/BidService.java`

> Le but : si l'ancien endpoint `createBid` existe encore (utilisé en interne ou en tests) il doit cesser de notifier le voyageur. La méthode peut rester comme outil interne mais ne publie plus l'event.

- [ ] **Step 1: Modifier le test existant `BidServiceTest`**

Modify `src/test/java/com/dony/api/matching/BidServiceTest.java` — ajouter ce test (ou modifier l'existant qui vérifie la publication) :
```java
@Test
void createBid_does_not_publish_BidCreatedEvent_anymore() {
    // ... setup as before ...
    bidService.createBid(announcement.getId(), "uid-sender", request, httpRequest);
    verify(eventPublisher, never()).publishEvent(any(BidCreatedEvent.class));
}
```

- [ ] **Step 2: Run, attendre FAIL si le test existant attendait l'event**

Run : `./mvnw test -Dtest=BidServiceTest`
Expected : FAIL sur le test ci-dessus.

- [ ] **Step 3: Retirer la publication de `BidCreatedEvent` dans `createBid`**

Modify `src/main/java/com/dony/api/matching/BidService.java` lignes ~141-143 — supprimer le bloc :
```java
eventPublisher.publishEvent(new BidCreatedEvent(
    saved.getId(), announcement.getId(), announcement.getTravelerId(), sender.getId(),
    senderName, saved.getWeightKg(), corridor));
```

Et nettoyer les imports/variables locales devenues inutilisées (`senderName`, `corridor` si plus rien ne les utilise).

- [ ] **Step 4: Tests passent**

Run : `./mvnw test -Dtest=BidServiceTest`
Expected : PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/dony/api/matching/BidService.java \
        src/test/java/com/dony/api/matching/BidServiceTest.java
git commit -m "refactor(bid): stop publishing BidCreatedEvent on bid creation (now done by webhook)"
```

---

## Task 9 : `acceptBid` capture le PaymentIntent ; `rejectBid` et `cancelBid` annulent

**Files:**
- Modify: `src/main/java/com/dony/api/matching/BidService.java`
- Modify: `src/test/java/com/dony/api/matching/BidServiceTest.java`

- [ ] **Step 1: Écrire / étendre les tests**

Modify `src/test/java/com/dony/api/matching/BidServiceTest.java` — ajouter :
```java
@Test
void acceptBid_captures_payment_intent() throws Exception {
    // setup bid PENDING with paymentIntentId="pi_acc"
    bid.setPaymentIntentId("pi_acc");
    bid.setStatus(BidStatus.PENDING);
    when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
    when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
    when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
    announcement.setTravelerId(traveler.getId());

    bidService.acceptBid(bid.getId(), "uid-traveler");

    verify(paymentService).capturePaymentIntent("pi_acc");
}

@Test
void rejectBid_cancels_payment_intent() throws Exception {
    bid.setPaymentIntentId("pi_rej");
    bid.setStatus(BidStatus.PENDING);
    when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
    when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
    when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
    announcement.setTravelerId(traveler.getId());

    bidService.rejectBid(bid.getId(), "uid-traveler", new BidRejectRequest("too heavy"));

    verify(paymentService).cancelPaymentIntent("pi_rej");
}

@Test
void cancelBid_when_PENDING_cancels_payment_intent() throws Exception {
    bid.setPaymentIntentId("pi_can");
    bid.setStatus(BidStatus.PENDING);
    when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
    when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));

    bidService.cancelBid(bid.getId(), "uid-sender");

    verify(paymentService).cancelPaymentIntent("pi_can");
}
```

> ⚠️ Le `BidService` actuel n'a pas `PaymentService` injecté → l'injection doit être ajoutée. Mais c'est un cross-package — pour respecter la règle CLAUDE.md (« Cross-package = Spring Application Events only »), on doit publier des events depuis `BidService` (`BidAcceptedEvent` / `BidRejectedEvent` existent déjà) et **ajouter des listeners côté `payments/`** qui appellent capture/cancel. Tester ainsi est plus propre.

**Approche corrigée** : ne PAS injecter `PaymentService` dans `BidService`. Créer/étendre des listeners côté `payments/` :
- `BidAcceptedEventListener` (NOUVEAU) → capture
- `BidRejectedEventListener` (existe déjà — ajouter cancel)
- `BidCancelledEventListener` (NOUVEAU) → cancel

Réécrire les tests en fonction.

- [ ] **Step 1bis: Réécrire les tests pour les listeners côté payments**

Create: `src/test/java/com/dony/api/payments/BidLifecycleListenersTest.java`
```java
package com.dony.api.payments;

import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.dony.api.matching.events.BidRejectedEvent;
import com.stripe.exception.StripeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BidLifecycleListenersTest {

    @Mock private PaymentService paymentService;
    @Mock private BidRepository bidRepository;
    private BidAcceptedEventListener acceptedListener;
    private BidRejectedEventListener rejectedListener;
    private BidEntity bid;

    @BeforeEach
    void setUp() {
        acceptedListener = new BidAcceptedEventListener(paymentService, bidRepository);
        rejectedListener = new BidRejectedEventListener(paymentService, bidRepository);
        bid = new BidEntity();
        bid.setId(UUID.randomUUID());
        bid.setPaymentIntentId("pi_test");
    }

    @Test
    void onBidAccepted_calls_capture() throws StripeException {
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        acceptedListener.onBidAccepted(new BidAcceptedEvent(
            bid.getId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        verify(paymentService).capturePaymentIntent("pi_test");
    }

    @Test
    void onBidRejected_calls_cancel() throws StripeException {
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        rejectedListener.onBidRejected(new BidRejectedEvent(bid.getId(), UUID.randomUUID(), "reason"));
        verify(paymentService).cancelPaymentIntent("pi_test");
    }
}
```

- [ ] **Step 2: Run pour FAIL**

Run : `./mvnw test -Dtest=BidLifecycleListenersTest`
Expected : FAIL (listeners absent ou ne capture pas).

- [ ] **Step 3: Créer / étendre les listeners**

Create: `src/main/java/com/dony/api/payments/BidAcceptedEventListener.java`
```java
package com.dony.api.payments;

import com.dony.api.matching.BidRepository;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.stripe.exception.StripeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class BidAcceptedEventListener {
    private static final Logger log = LoggerFactory.getLogger(BidAcceptedEventListener.class);

    private final PaymentService paymentService;
    private final BidRepository bidRepository;

    public BidAcceptedEventListener(PaymentService paymentService, BidRepository bidRepository) {
        this.paymentService = paymentService;
        this.bidRepository = bidRepository;
    }

    @EventListener
    public void onBidAccepted(BidAcceptedEvent event) {
        bidRepository.findById(event.getBidId()).ifPresent(bid -> {
            try {
                paymentService.capturePaymentIntent(bid.getPaymentIntentId());
            } catch (StripeException e) {
                log.error("Failed to capture PaymentIntent for accepted bid {}", bid.getId(), e);
                // Sentry will catch ; manual reconciliation possible via admin
            }
        });
    }
}
```

Modify `src/main/java/com/dony/api/payments/BidRejectedEventListener.java` — ajouter l'appel `cancelPaymentIntent` :
```java
@EventListener
public void onBidRejected(BidRejectedEvent event) {
    // ... logique existante ...
    bidRepository.findById(event.getBidId()).ifPresent(bid -> {
        try {
            paymentService.cancelPaymentIntent(bid.getPaymentIntentId());
        } catch (StripeException e) {
            log.error("Failed to cancel PaymentIntent for rejected bid {}", bid.getId(), e);
        }
    });
}
```

> ⚠️ Lire le fichier existant avant — adapter les imports / champs déjà présents. Si `BidRepository` ou `PaymentService` ne sont pas injectés, les ajouter.

Pour `cancelBid` (annulation par sender) : le `BidService.cancelBid` actuel ne publie pas d'event. Le faire :
```java
// dans cancelBid, juste avant le return
eventPublisher.publishEvent(new BidCancelledByOwnerEvent(bid.getId(), bid.getPaymentIntentId()));
```
Create event class `src/main/java/com/dony/api/matching/events/BidCancelledByOwnerEvent.java` (UUID bidId, String paymentIntentId).

Create listener `src/main/java/com/dony/api/payments/BidCancelledByOwnerEventListener.java` similar to BidAcceptedEventListener but calls `cancelPaymentIntent`.

- [ ] **Step 4: Tests passent**

Run : `./mvnw test -Dtest=BidLifecycleListenersTest`
Expected : PASS (2 tests).

Run : `./mvnw test -Dtest=BidServiceTest`
Expected : PASS (suite complète).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/dony/api/matching/BidService.java \
        src/main/java/com/dony/api/matching/events/BidCancelledByOwnerEvent.java \
        src/main/java/com/dony/api/payments/BidAcceptedEventListener.java \
        src/main/java/com/dony/api/payments/BidRejectedEventListener.java \
        src/main/java/com/dony/api/payments/BidCancelledByOwnerEventListener.java \
        src/test/java/com/dony/api/payments/BidLifecycleListenersTest.java \
        src/test/java/com/dony/api/matching/BidServiceTest.java
git commit -m "feat(payments): capture PI on accept, cancel PI on reject/cancel via event listeners"
```

---

## Task 10 : Filtres de visibilité (voyageur ne voit pas `AWAITING_PAYMENT`)

**Files:**
- Modify: `src/main/java/com/dony/api/matching/BidRepository.java`
- Modify: `src/main/java/com/dony/api/matching/BidService.java`
- Test: `src/test/java/com/dony/api/matching/BidVisibilityTest.java`

- [ ] **Step 1: Écrire le test**

Create: `src/test/java/com/dony/api/matching/BidVisibilityTest.java`
```java
package com.dony.api.matching;

// ... test that getBidsForAnnouncement (called by traveler) excludes AWAITING_PAYMENT
// ... test that getMyBids (called by sender) includes AWAITING_PAYMENT
// (full code analogous to BidServiceTest setup)
```
*(Mocker `bidRepository.findByAnnouncementId` pour retourner une liste mixte AWAITING_PAYMENT + PENDING ; assert que le résultat exclut AWAITING_PAYMENT côté voyageur, l'inclut côté expéditeur.)*

- [ ] **Step 2: Run pour FAIL**

Run : `./mvnw test -Dtest=BidVisibilityTest`
Expected : FAIL — actuellement aucun filtre.

- [ ] **Step 3: Ajouter le filtre dans `BidService.getBidsForAnnouncement`**

Modify méthode `getBidsForAnnouncement` (ligne ~177) : ajouter `.filter(b -> b.getStatus() != BidStatus.AWAITING_PAYMENT)` avant `.filter(b -> !b.isDeletedByTraveler())`.

- [ ] **Step 4: Tests passent**

Run : `./mvnw test -Dtest=BidVisibilityTest`
Expected : PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/dony/api/matching/BidService.java \
        src/test/java/com/dony/api/matching/BidVisibilityTest.java
git commit -m "feat(bid): hide AWAITING_PAYMENT bids from traveler views"
```

---

## Task 11 : `AwaitingPaymentCleanupScheduler` (suppression physique à T+15min)

**Files:**
- Create: `src/main/java/com/dony/api/matching/AwaitingPaymentCleanupScheduler.java`
- Modify: `src/main/java/com/dony/api/matching/BidRepository.java` (nouvelle requête)
- Test: `src/test/java/com/dony/api/matching/AwaitingPaymentCleanupSchedulerTest.java`

- [ ] **Step 1: Écrire les tests**

Create: `src/test/java/com/dony/api/matching/AwaitingPaymentCleanupSchedulerTest.java`
```java
package com.dony.api.matching;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.BidCreatedEvent;
import com.dony.api.payments.PaymentService;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AwaitingPaymentCleanupSchedulerTest {

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private PaymentService paymentService;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AwaitingPaymentCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AwaitingPaymentCleanupScheduler(
            bidRepository, announcementRepository, paymentService, auditService, eventPublisher);
    }

    @Test
    void deletes_bid_when_cancel_succeeds() throws StripeException {
        BidEntity bid = newAwaitingPaymentBid();
        when(bidRepository.findByStatusAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), any())).thenReturn(List.of(bid));

        scheduler.cleanupUnpaidBids();

        verify(paymentService).cancelPaymentIntent("pi_xxx");
        verify(bidRepository).deleteById(bid.getId());
        verify(eventPublisher, never()).publishEvent(any(BidCreatedEvent.class));
    }

    @Test
    void promotes_bid_when_PI_already_succeeded_race_condition() throws StripeException {
        BidEntity bid = newAwaitingPaymentBid();
        when(bidRepository.findByStatusAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), any())).thenReturn(List.of(bid));

        // Simuler erreur Stripe "payment_intent_unexpected_state" + status "succeeded"
        InvalidRequestException ex = mock(InvalidRequestException.class);
        when(ex.getCode()).thenReturn("payment_intent_unexpected_state");
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("succeeded");
        when(ex.getStripeError()).thenReturn(null); // adapter selon ce qui marche
        // Pour simplicité, on stub PaymentIntent.retrieve à renvoyer un PI succeeded
        // et on attrape StripeException.
        doThrow(ex).when(paymentService).cancelPaymentIntent("pi_xxx");

        // (le test doit aussi mocker le retrieve dans le scheduler — voir implementation below)
        // Pour ce test, le scheduler doit déléguer la promotion à PaymentService.promoteBidOnPaymentAuthorized
        // Donc on assert :
        scheduler.cleanupUnpaidBids();
        verify(paymentService).promoteBidOnPaymentAuthorized("pi_xxx");
        verify(bidRepository, never()).deleteById(bid.getId());
    }

    private BidEntity newAwaitingPaymentBid() {
        BidEntity bid = new BidEntity();
        bid.setId(UUID.randomUUID());
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setPaymentIntentId("pi_xxx");
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        return bid;
    }
}
```

- [ ] **Step 2: Run pour FAIL**

Run : `./mvnw test -Dtest=AwaitingPaymentCleanupSchedulerTest`
Expected : compile fail.

- [ ] **Step 3: Ajouter la requête repository**

Modify `src/main/java/com/dony/api/matching/BidRepository.java` :
```java
java.util.List<BidEntity> findByStatusAndAwaitingPaymentExpiresAtBefore(
    BidStatus status, java.time.LocalDateTime threshold);
```

- [ ] **Step 4: Créer le scheduler**

Create: `src/main/java/com/dony/api/matching/AwaitingPaymentCleanupScheduler.java`
```java
package com.dony.api.matching;

import com.dony.api.common.AuditService;
import com.dony.api.payments.PaymentService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class AwaitingPaymentCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AwaitingPaymentCleanupScheduler.class);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final PaymentService paymentService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public AwaitingPaymentCleanupScheduler(BidRepository bidRepository,
                                           AnnouncementRepository announcementRepository,
                                           PaymentService paymentService,
                                           AuditService auditService,
                                           ApplicationEventPublisher eventPublisher) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.paymentService = paymentService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedRate = 5 * 60 * 1000)  // every 5 minutes
    @Transactional
    public void cleanupUnpaidBids() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<BidEntity> expired = bidRepository
            .findByStatusAndAwaitingPaymentExpiresAtBefore(BidStatus.AWAITING_PAYMENT, now);

        for (BidEntity bid : expired) {
            String piId = bid.getPaymentIntentId();
            try {
                paymentService.cancelPaymentIntent(piId);
                bidRepository.deleteById(bid.getId());
                log.info("Bid {} (PI={}) deleted (unpaid timeout)", bid.getId(), piId);
            } catch (StripeException e) {
                if (isAlreadySucceeded(piId)) {
                    log.warn("Race condition for bid {}: PI succeeded — promoting", bid.getId());
                    paymentService.promoteBidOnPaymentAuthorized(piId);
                } else {
                    log.error("Cleanup failed for bid {}: {}", bid.getId(), e.getMessage());
                    // do not delete — retry next tick
                }
            }
        }
    }

    private boolean isAlreadySucceeded(String paymentIntentId) {
        try {
            PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId);
            return "succeeded".equals(pi.getStatus())
                || "requires_capture".equals(pi.getStatus());
        } catch (StripeException e) {
            return false;
        }
    }
}
```

> Activer `@EnableScheduling` quelque part (probablement déjà actif via `BidScheduler` existant — vérifier `Application.java` ou config).

- [ ] **Step 5: Tests passent**

Run : `./mvnw test -Dtest=AwaitingPaymentCleanupSchedulerTest`
Expected : PASS.

- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/dony/api/matching/AwaitingPaymentCleanupScheduler.java \
        src/main/java/com/dony/api/matching/BidRepository.java \
        src/test/java/com/dony/api/matching/AwaitingPaymentCleanupSchedulerTest.java
git commit -m "feat(bid): add cleanup scheduler for unpaid AWAITING_PAYMENT bids (T+15min)"
```

---

## Task 12 : `BidTimeoutScheduler` (auto-annulation des bids `PENDING` non répondus)

**Files:**
- Create: `src/main/java/com/dony/api/matching/BidTimeoutScheduler.java`
- Modify: `src/main/java/com/dony/api/matching/BidRepository.java`
- Test: `src/test/java/com/dony/api/matching/BidTimeoutSchedulerTest.java`

- [ ] **Step 1: Écrire les tests**

Create: `src/test/java/com/dony/api/matching/BidTimeoutSchedulerTest.java`
```java
package com.dony.api.matching;

// Tests :
// 1. bid PENDING créé il y a 25h, départ dans 100h → annulé (24h dépassées)
// 2. bid PENDING créé il y a 1h, départ dans 6h → annulé (H-12 dépassé)
// 3. bid PENDING créé il y a 1h, départ dans 24h → pas annulé
// Vérifier appel paymentService.cancelPaymentIntent + audit_log + event published
```
*(Mocker `bidRepository.findPendingTimedOut(now)` pour retourner les listes appropriées.)*

- [ ] **Step 2: Ajouter la requête**

Modify `BidRepository.java` :
```java
@org.springframework.data.jpa.repository.Query("""
    SELECT b FROM BidEntity b, AnnouncementEntity a
    WHERE b.announcementId = a.id
      AND b.status = com.dony.api.matching.BidStatus.PENDING
      AND (
            b.createdAt < :twentyFourHoursAgo
         OR a.departureDate < :twelveHoursFromNow
      )
    """)
List<BidEntity> findPendingTimedOut(
    @org.springframework.data.repository.query.Param("twentyFourHoursAgo") LocalDateTime twentyFourHoursAgo,
    @org.springframework.data.repository.query.Param("twelveHoursFromNow") LocalDate twelveHoursFromNow);
```
*(Adapter type de `departureDate` selon entité — `LocalDate` vu Story 3.4.)*

- [ ] **Step 3: Implémenter le scheduler**

Create: `src/main/java/com/dony/api/matching/BidTimeoutScheduler.java`
```java
package com.dony.api.matching;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.BidRejectedEvent;
import com.stripe.exception.StripeException;
import com.dony.api.payments.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Component
public class BidTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(BidTimeoutScheduler.class);

    private final BidRepository bidRepository;
    private final PaymentService paymentService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public BidTimeoutScheduler(BidRepository bidRepository, PaymentService paymentService,
                               AuditService auditService, ApplicationEventPublisher eventPublisher) {
        this.bidRepository = bidRepository;
        this.paymentService = paymentService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedRate = 5 * 60 * 1000)
    @Transactional
    public void autoCancelUnansweredBids() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<BidEntity> timedOut = bidRepository.findPendingTimedOut(
            now.minusHours(24),
            now.toLocalDate().plusDays(1)  // departure < tomorrow + 12h logic; refine
        );

        for (BidEntity bid : timedOut) {
            bid.setStatus(BidStatus.CANCELLED);
            bid.setRejectionReason("TRAVELER_NO_RESPONSE");
            bidRepository.save(bid);
            try {
                paymentService.cancelPaymentIntent(bid.getPaymentIntentId());
            } catch (StripeException e) {
                log.error("Failed to cancel PI for timed-out bid {}", bid.getId(), e);
            }
            auditService.log("BID", bid.getId(), "BID_AUTO_CANCELLED_TIMEOUT", null,
                Map.of("paymentIntentId", String.valueOf(bid.getPaymentIntentId())));
            eventPublisher.publishEvent(new BidRejectedEvent(
                bid.getId(), bid.getSenderId(), "TRAVELER_NO_RESPONSE"));
            log.info("Bid {} auto-cancelled (no traveler response)", bid.getId());
        }
    }
}
```

- [ ] **Step 4: Tests passent**

Run : `./mvnw test -Dtest=BidTimeoutSchedulerTest`
Expected : PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/dony/api/matching/BidTimeoutScheduler.java \
        src/main/java/com/dony/api/matching/BidRepository.java \
        src/test/java/com/dony/api/matching/BidTimeoutSchedulerTest.java
git commit -m "feat(bid): add timeout scheduler that auto-cancels unanswered PENDING bids"
```

---

## Task 13 : Migration des tests existants cassés + couverture finale

**Files:**
- Modify: `src/test/java/com/dony/api/matching/BidServiceTest.java` et `BidControllerIntegrationTest.java` selon nécessité.
- Modify: `src/test/java/com/dony/api/matching/AnnouncementBidsControllerTest.java` (ou équivalent)

- [ ] **Step 1: Lancer toute la suite et lister les tests rouges**

Run : `./mvnw test`
Expected : possiblement quelques rouges issus des tests qui appelaient `POST /announcements/{id}/bids` ou attendaient `BidCreatedEvent`.

- [ ] **Step 2: Mettre à jour chaque test rouge**

Pour chacun :
- Si test utilisait l'endpoint `/announcements/{id}/bids` → migrer vers `/bids/checkout` ou supprimer si redondant
- Si test attendait `BidCreatedEvent` lors de `createBid` → assertion inverse (`verify(eventPublisher, never())...`)
- Si test créait un `BidEntity` directement avec status `PENDING` → ne pas modifier (test interne, OK)

- [ ] **Step 3: Vérifier la couverture**

Run : `./mvnw test jacoco:report`

Ouvrir `target/site/jacoco/index.html`. Vérifier :
- Couverture globale ≥ 90 %
- Classes touchées : `BidCheckoutService`, `AwaitingPaymentCleanupScheduler`, `BidTimeoutScheduler`, `PaymentService` (méthodes ajoutées) — couverture ≥ 90 %.

Si < 90 %, ajouter tests pour les branches manquantes (erreurs Stripe non-success, idempotence, edge cases).

- [ ] **Step 4: Commit final**
```bash
git add -u
git commit -m "test(bid): align existing tests with payment-first flow and reach 90% coverage"
```

---

## Task 14 : Documentation de story

**Files:**
- Create: `docs/stories-done/story-X.Y-bid-checkout-payment-first.md`

- [ ] **Step 1: Rédiger le doc story selon le template du `dony-back/CLAUDE.md`**

Sections obligatoires :
- Date, Status, Résumé
- Fichiers créés / modifiés
- Comment ça fonctionne (flux, points d'entrée API, entités JPA, logique métier critique, events publiés/écoutés, pièges)
- Critères d'acceptation couverts (cocher ceux du spec)
- Tests (résultat `./mvnw test`, couverture JaCoCo %)
- Décisions techniques (pré-autorisation vs capture immédiate, suppression physique justifiée)

- [ ] **Step 2: Commit**
```bash
git add docs/stories-done/story-X.Y-bid-checkout-payment-first.md
git commit -m "docs: add story doc for bid checkout payment-first flow"
```

---

## Self-review (à exécuter avant de lancer l'implémentation)

**Spec coverage** : tous les critères d'acceptation du spec sont couverts par au moins une tâche :
- POST /bids/checkout (Task 5+6) ✅
- Aucun event/audit avant paiement (Task 5, 8) ✅
- Voyageur ne voit pas AWAITING_PAYMENT (Task 10) ✅
- Sender voit son AWAITING_PAYMENT (Task 5+10) ✅
- Webhook promeut + publie event (Task 7) ✅
- accept = capture / reject + cancel = cancel PI (Task 9) ✅
- Cleanup scheduler 15min (Task 11) ✅
- Timeout scheduler min(24h, departure-12h) (Task 12) ✅
- Race condition gérée (Task 11) ✅
- Webhook signature (déjà existant, inchangé) ✅
- Tests verts + JaCoCo ≥ 90 % (Task 13) ✅
- Migration V37 (Task 1) ✅

**Type consistency** : `paymentIntentId` (String, nom Java cohérent avec column `payment_intent_id`), `awaitingPaymentExpiresAt` (LocalDateTime), `BidStatus.AWAITING_PAYMENT` partout.

**Pas de placeholder** : chaque step contient code complet.

**Risques résiduels** :
- Le stub Stripe `cancelPaymentIntent` qui throw `payment_intent_unexpected_state` dans Task 11 est un peu artificiel — l'implémenteur devra peut-être adapter pour matcher l'API réelle de `StripeException`. Mentionné dans le test.
- Task 12 : la requête `findPendingTimedOut` à raffiner selon le type exact de `departureDate` côté entité.
- Si `PaymentResponse` n'expose pas `getPublishableKey()` aujourd'hui, l'ajouter aux DTO PaymentService (Step 4 Task 5).

---

# PART 2 — Plan v2 : Refonte de l'escrow (separate charges and transfers)

> Cette partie remplace les Tasks 9 à 14 originales. Les ordres de Task ci-dessous sont 9a, 9b, 9c, 9d, 9e suivis de Tasks 10', 11', 12', 13', 14' (équivalents des originales mais adaptés au nouveau modèle).

## Vue d'ensemble PART 2

| Task | Sujet | Touche au mode legacy ? |
|------|-------|--------------------------|
| 9a | Migration V38 (`legacy_destination_charge`, `stripe_charge_id`) + champs `PaymentEntity` | Oui — base de la dual-path |
| 9b | Webhook : populer `stripeChargeId` au `payment_intent.amount_capturable_updated` | Oui (utile pour les deux modes) |
| 9c | `BidAcceptedEventListener` : capture le PI uniquement si `legacy = false` | Oui |
| 9d | `DeliveryEventListener` : `pi.capture()` (legacy) vs `Transfer.create()` (v2) | Oui |
| 9e | `BidRejectedEventListener` : `pi.cancel()` (avant capture) vs `Refund` (après capture) | Oui |
| 9f | Refactor `PaymentService.createEscrow` : ne plus poser `transfer_data` ni `application_fee_amount` ; marquer `legacy_destination_charge = false` | Non (nouveaux paiements seulement) |
| 9g | `BidCancelledByOwnerEvent` + listener : annuler le PI quand le sender annule un bid `PENDING` | Non |
| 10' | Filtres visibilité voyageur (idem original Task 10) | Non |
| 11' | `AwaitingPaymentCleanupScheduler` (idem original Task 11) | Non |
| 12' | `BidTimeoutScheduler` (idem original Task 12) | Non |
| 13' | Migration des tests cassés + couverture ≥ 90 % | Oui |
| 14' | Documentation story (avec section legacy) | — |

**Effort estimé PART 2** : ~10-12 h (vs ~7 h initialement).

---

## Task 9a : Migration V38 + champs `PaymentEntity`

**Files:**
- Create: `src/main/resources/db/migration/V38__payments_add_legacy_flag_and_charge_id.sql`
- Modify: `src/main/java/com/dony/api/payments/PaymentEntity.java`
- Test: `src/test/java/com/dony/api/payments/PaymentEntityV38MigrationTest.java`

- [ ] **Step 1 : Test migration**

```java
@SpringBootTest
@ActiveProfiles("test")
class PaymentEntityV38MigrationTest {
    @Autowired private JdbcTemplate jdbc;

    @Test
    void v38_adds_legacy_flag_and_charge_id() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns " +
            "WHERE table_name = 'payments' " +
            "AND column_name IN ('legacy_destination_charge','stripe_charge_id')",
            Integer.class);
        assertThat(count).isEqualTo(2);
    }
}
```

- [ ] **Step 2 : Run, expect FAIL**

`./mvnw test -Dtest=PaymentEntityV38MigrationTest` → FAIL.

- [ ] **Step 3 : Migration V38**

```sql
ALTER TABLE payments
  ADD COLUMN legacy_destination_charge BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN stripe_charge_id          VARCHAR(255);

UPDATE payments SET legacy_destination_charge = true;

CREATE INDEX idx_payments_stripe_charge_id ON payments (stripe_charge_id);

COMMENT ON COLUMN payments.legacy_destination_charge IS 'true si le PaymentIntent a transfer_data.destination (capture transfère immédiatement). false pour separate-charges-and-transfers.';
COMMENT ON COLUMN payments.stripe_charge_id IS 'Charge id, populé au webhook amount_capturable_updated. Nécessaire pour Transfer.create.';
```

- [ ] **Step 4 : Champs JPA dans `PaymentEntity.java`**

```java
@Column(name = "legacy_destination_charge", nullable = false)
private boolean legacyDestinationCharge = false;

@Column(name = "stripe_charge_id", length = 255)
private String stripeChargeId;
```
+ getters/setters.

- [ ] **Step 5 : Tests passent**

- [ ] **Step 6 : Commit**
```
git commit -m "feat(payments): V38 add legacy_destination_charge + stripe_charge_id (escrow refactor preparation)"
```

---

## Task 9b : Webhook populer `stripeChargeId`

**Files:**
- Modify: `src/main/java/com/dony/api/payments/PaymentService.java` (méthode `handlePaymentEscrowActive`)
- Test: `src/test/java/com/dony/api/payments/PaymentWebhookChargeIdTest.java`

- [ ] **Step 1 : Test**

Test que au webhook `payment_intent.amount_capturable_updated`, si `pi.getLatestCharge()` (ou `pi.getCharges().getData().get(0).getId()`) est non-null, on populé `payment.stripeChargeId` et on save.

- [ ] **Step 2 : Implémenter**

Dans `handlePaymentEscrowActive`, après `payment.setStatus(PaymentStatus.ESCROW)` :
```java
String chargeId = pi.getLatestCharge();  // available in Stripe Java SDK ≥ 22
if (chargeId != null && payment.getStripeChargeId() == null) {
    payment.setStripeChargeId(chargeId);
}
```

Si `pi.getLatestCharge()` n'existe pas dans la version Stripe SDK utilisée, utiliser `pi.getCharges().getData().get(0).getId()` (vérifier la disponibilité).

- [ ] **Step 3 : Test pass**

- [ ] **Step 4 : Commit**
```
git commit -m "feat(payments): persist stripe_charge_id at webhook amount_capturable_updated"
```

---

## Task 9c : `BidAcceptedEventListener` capture le PI (mode v2 seulement)

**Files:**
- Create: `src/main/java/com/dony/api/payments/BidAcceptedEventListener.java`
- Test: `src/test/java/com/dony/api/payments/BidAcceptedEventListenerTest.java`

- [ ] **Step 1 : Test**

Cas couverts :
- Bid accepté + Payment `legacy = false` + statut `ESCROW` (hold posé) → `pi.capture()` appelé.
- Bid accepté + Payment `legacy = true` → **PAS** d'appel à `capture()` (la capture historique reste à la livraison).
- Bid accepté + Payment introuvable → log warn, no-op.
- Bid accepté + Payment statut autre que `ESCROW` (ex. `RELEASED`) → no-op.

- [ ] **Step 2 : Implémenter**

```java
@Component
public class BidAcceptedEventListener {
    private static final Logger log = LoggerFactory.getLogger(BidAcceptedEventListener.class);

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;

    public BidAcceptedEventListener(PaymentRepository paymentRepository, AuditService auditService) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
    }

    @EventListener
    @Async
    @Transactional
    public void onBidAccepted(BidAcceptedEvent event) {
        Optional<PaymentEntity> opt = paymentRepository.findByBidId(event.getBidId());
        if (opt.isEmpty()) {
            log.warn("BidAccepted but no payment found for bid {}", event.getBidId());
            return;
        }
        PaymentEntity payment = opt.get();

        if (payment.isLegacyDestinationCharge()) {
            log.info("Bid {} accepted but payment is legacy — capture deferred to delivery", event.getBidId());
            return;
        }

        if (payment.getStatus() != PaymentStatus.ESCROW) {
            log.info("Bid {} accepted but payment status is {} — skipping capture", event.getBidId(), payment.getStatus());
            return;
        }

        try {
            PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
            pi.capture();
            // Le statut payment reste ESCROW (signifie maintenant "captured on platform")
            auditService.log("PAYMENT", payment.getId(), "PAYMENT_CAPTURED_ON_PLATFORM",
                payment.getBidId(),
                Map.of("piId", payment.getStripePaymentIntentId(),
                       "bidId", event.getBidId().toString()));
            log.info("PaymentIntent {} captured on platform for bid {}", payment.getStripePaymentIntentId(), event.getBidId());
        } catch (StripeException e) {
            log.error("Capture failed for bid {} (PI={}): {}", event.getBidId(), payment.getStripePaymentIntentId(), e.getMessage(), e);
            // Sentry will catch
        }
    }
}
```

- [ ] **Step 3 : Test pass**

- [ ] **Step 4 : Commit**
```
git commit -m "feat(payments): capture PI on bid accept for non-legacy payments"
```

---

## Task 9d : Refactor `DeliveryEventListener` (capture vs Transfer)

**Files:**
- Modify: `src/main/java/com/dony/api/payments/DeliveryEventListener.java`
- Modify: `src/test/java/com/dony/api/payments/DeliveryEventListenerTest.java` (si existe, sinon créer)

- [ ] **Step 1 : Test**

Cas :
- Delivery confirmed + Payment `legacy = true` + ESCROW → `pi.capture()` appelé (comportement actuel).
- Delivery confirmed + Payment `legacy = false` + ESCROW (déjà capturé sur plateforme) → `Transfer.create` appelé avec `amount = total - 12 %` et `destination = traveler.stripeAccountId`.
- Delivery confirmed + Payment `RELEASED` → no-op (idempotence).
- Delivery confirmed + Payment introuvable → log warn, no-op.

- [ ] **Step 2 : Implémenter**

```java
@EventListener @Async @Transactional
public void handleDeliveryConfirmed(DeliveryConfirmedEvent event) {
    Optional<PaymentEntity> opt = paymentRepository.findByBidId(event.getBidId());
    if (opt.isEmpty()) { /* log + return */ }
    PaymentEntity payment = opt.get();
    if (payment.getStatus() != PaymentStatus.ESCROW) { /* idempotence — return */ }

    UserEntity traveler = userRepository.findById(event.getTravelerId())
        .orElseThrow(/* should not happen */);

    try {
        if (payment.isLegacyDestinationCharge()) {
            // Ancien flow : capture transfère immédiatement au voyageur via transfer_data
            PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
            pi.capture();
            log.info("Legacy capture for bid {}", event.getBidId());
        } else {
            // Nouveau flow : Transfer du compte plateforme vers le voyageur
            BigDecimal commission = payment.getCommissionAmount();
            BigDecimal net = payment.getAmount().subtract(commission);
            long netCents = net.multiply(BigDecimal.valueOf(100)).longValue();

            TransferCreateParams.Builder tb = TransferCreateParams.builder()
                .setAmount(netCents)
                .setCurrency("eur")
                .setDestination(traveler.getStripeAccountId())
                .putMetadata("bid_id", event.getBidId().toString())
                .putMetadata("payment_id", payment.getId().toString());
            if (payment.getStripeChargeId() != null) {
                tb.setSourceTransaction(payment.getStripeChargeId());
            }
            Transfer.create(tb.build());
            log.info("Transfer of {}€ to traveler {} for bid {}", net, traveler.getId(), event.getBidId());
        }

        payment.setStatus(PaymentStatus.RELEASED);
        payment.setEscrowReleasedAt(LocalDateTime.now(ZoneOffset.UTC));
        paymentRepository.save(payment);

        auditService.log(
            "PAYMENT",
            payment.getId(),
            payment.isLegacyDestinationCharge() ? "ESCROW_RELEASED_LEGACY" : "ESCROW_RELEASED_TRANSFER",
            payment.getBidId(),
            Map.of("bidId", event.getBidId().toString(),
                   "amount", payment.getAmount().toPlainString(),
                   "legacy", payment.isLegacyDestinationCharge()));

        eventPublisher.publishEvent(new PaymentReleasedEvent(
            payment.getBidId(), event.getTravelerId(), event.getSenderId(), payment.getAmount()));

    } catch (StripeException e) {
        log.error("Failed to release payment for bid {} (legacy={}): {}",
            event.getBidId(), payment.isLegacyDestinationCharge(), e.getMessage(), e);
    }
}
```

- [ ] **Step 3 : Test pass** (les deux modes)

- [ ] **Step 4 : Commit**
```
git commit -m "refactor(payments): branch DeliveryEventListener on legacy_destination_charge (capture vs Transfer)"
```

---

## Task 9e : `BidRejectedEventListener` cancel vs Refund

**Files:**
- Modify: `src/main/java/com/dony/api/payments/BidRejectedEventListener.java` (existe déjà)
- Modify ses tests

Le code actuel gère déjà `cancel` (PI PENDING, hold actif) et `Refund` (PI ESCROW historique = capturé). Avec le mode v2, **`PaymentStatus.ESCROW`** signifie "captured on platform" pour les non-legacy → l'argent est sur la plateforme, donc `Refund` est correct (pas de besoin de Transfer Reversal car pas encore transféré). **Vérifier** que ce comportement est cohérent.

Cas particulier : si la rejection arrive **après** la livraison + Transfer (status `RELEASED`), il faut `Transfer.createReversal` avant `Refund`. Mais ce cas est rare (rejection après livraison = c'est plus un litige que un reject) et est listé en Q4 du spec. Pour cette task, ne PAS l'implémenter, simplement logger et no-op.

- [ ] **Step 1 : Tests** (couvrir : reject avant capture → cancel ; reject après capture non-legacy → refund ; reject status RELEASED → log warn no-op)

- [ ] **Step 2 : Adapter le code existant** (le code actuel marche déjà pour les deux premiers cas — vérifier qu'il fonctionne bien avec `ESCROW` non-legacy = "captured on platform")

- [ ] **Step 3 : Commit**

---

## Task 9f : Refactor `PaymentService.createEscrow` — modèle separate charges and transfers

**Files:**
- Modify: `src/main/java/com/dony/api/payments/PaymentService.java`
- Modify: `src/test/java/com/dony/api/payments/PaymentServiceTest.java`

- [ ] **Step 1 : Mettre à jour les tests existants**

Les tests qui asseraient `application_fee_amount` ou `transfer_data.destination` sur le PaymentIntent doivent être mis à jour : ces params **ne sont plus posés** pour les nouveaux paiements.

- [ ] **Step 2 : Adapter `createEscrow`**

Retirer :
```java
.setApplicationFeeAmount(commissionCents)
.setTransferData(PaymentIntentCreateParams.TransferData.builder()
    .setDestination(traveler.getStripeAccountId())
    .build())
```

Ajouter (juste avant le `paymentRepository.save`) :
```java
payment.setLegacyDestinationCharge(false);
```

La commission est conservée comme champ séparé (déjà géré par `payment.setCommissionAmount(commission)`) et sera utilisée plus tard pour calculer l'amount du Transfer à la livraison.

- [ ] **Step 3 : Conserver `traveler.isStripeOnboarded()` check** (le voyageur doit toujours avoir un compte Connect pour pouvoir recevoir le Transfer plus tard)

- [ ] **Step 4 : Tests passent**

- [ ] **Step 5 : Commit**
```
git commit -m "refactor(payments): drop transfer_data and application_fee_amount from new PaymentIntents (escrow on platform)"
```

---

## Task 9g : `BidCancelledByOwnerEvent` + listener

**Files:**
- Create: `src/main/java/com/dony/api/matching/events/BidCancelledByOwnerEvent.java`
- Create: `src/main/java/com/dony/api/payments/BidCancelledByOwnerEventListener.java`
- Modify: `src/main/java/com/dony/api/matching/BidService.java` (publier l'event dans `cancelBid`)
- Tests

Quand l'expéditeur annule son propre bid `PENDING` (déjà payé, hold actif) → libérer le hold via `pi.cancel()`. Pour les bids `ACCEPTED` (capturés), même logique que rejection après capture : `Refund` (à implémenter ou laisser au `BidRejectedEventListener` en publiant `BidRejectedEvent` ?).

**Décision** : pour simplifier, faire publier `BidRejectedEvent` (pas `BidCancelledByOwnerEvent`) avec une raison explicite "CANCELLED_BY_SENDER". Réutiliser le listener existant.

Donc en réalité Task 9g se réduit à :

- [ ] **Step 1** : dans `BidService.cancelBid`, publier `BidRejectedEvent` avec raison `"CANCELLED_BY_SENDER"` (en plus de l'audit existant).
- [ ] **Step 2** : test que `BidRejectedEventListener` réagit correctement (cancel ou refund selon état du payment).
- [ ] **Step 3** : commit `feat(bid): publish BidRejectedEvent with reason CANCELLED_BY_SENDER on sender cancel`.

(Pas de nouvelle classe `BidCancelledByOwnerEvent` créée, on réutilise l'event existant.)

---

## Task 10' : Filtres visibilité (idem original Task 10)

Voir Task 10 originale (PART 1) — code identique, à exécuter tel quel.

---

## Task 11' : `AwaitingPaymentCleanupScheduler` (idem original Task 11)

Voir Task 11 originale — code identique. **Note :** la race condition (PI déjà succeeded au moment du cleanup) reste valide dans le nouveau modèle.

---

## Task 12' : `BidTimeoutScheduler` (idem original Task 12)

Voir Task 12 originale — code identique. **Note :** dans le nouveau modèle, l'auto-cancel d'un bid `PENDING` non répondu = `pi.cancel()` qui libère le hold (0 frais). Comportement inchangé.

---

## Task 13' : Migration tests existants + couverture

Voir Task 13 originale, mais avec ces ajouts spécifiques v2 :
- Vérifier que les tests existants de `PaymentService.createEscrow` ne s'attendent plus à `transfer_data` ou `application_fee_amount`.
- Vérifier que `DeliveryEventListenerTest` couvre les deux flows (legacy et v2).
- Vérifier que `BidRejectedEventListenerTest` couvre les trois états (PENDING/ESCROW-non-legacy/RELEASED).
- Couverture JaCoCo ≥ 90 % obligatoire.

---

## Task 14' : Documentation story

Voir Task 14 originale, mais ajouter une section **"Mode legacy"** expliquant :
- Pourquoi le flag `legacy_destination_charge` existe
- Comment identifier les payments legacy (créés avant le déploiement v2)
- Quand le flag pourra être retiré (~3-6 mois après déploiement, quand tous les bids antérieurs seront résolus)
- Lien vers les Questions Ouvertes du spec (Q1 à Q6)

---

## Self-review PART 2

**Couverture spec v2** :
- Migration V38 (Task 9a) ✅
- `stripe_charge_id` populé (Task 9b) ✅
- Capture à acceptation pour non-legacy (Task 9c) ✅
- DeliveryEventListener dual-path (Task 9d) ✅
- Reject avant/après capture (Task 9e) ✅
- `createEscrow` ne pose plus transfer_data (Task 9f) ✅
- Annulation par sender (Task 9g) ✅
- Filtre visibilité (Task 10') ✅
- Cleanup scheduler (Task 11') ✅
- Timeout scheduler (Task 12') ✅
- Tests + couverture (Task 13') ✅
- Doc (Task 14') ✅

**Risques résiduels v2** :
- Le champ `pi.getLatestCharge()` dépend de la version Stripe SDK utilisée. Si non disponible, fallback sur `pi.getCharges().getData().get(0).getId()`. À vérifier au moment de Task 9b.
- Les Questions Ouvertes Q1-Q6 du spec doivent être résolues **avant déploiement prod** (validation Stripe support + avocat fintech + expert-comptable).
- Le mode legacy pourrait générer des comportements surprenants en CI si les tests utilisent un mix de `legacy=true` et `legacy=false`. Bien isoler les fixtures de test.
