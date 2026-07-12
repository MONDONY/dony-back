# Moteur d'exécution des automatisations — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rendre fonctionnelles 5 des 6 règles d'automatisation préconfigurées de `dony-pro` (la 6e — fermeture auto à capacité 0 — est déjà acquise, hors scope) : un moteur backend qui évalue les règles activées par chaque voyageur et agit réellement (accepter/refuser un bid, notifier), plus l'UI de configuration des seuils côté web.

**Architecture:** Package `com.dony.api.automation` (dony-back) enrichi de listeners Spring Event (`@EventListener`/`@TransactionalEventListener`) réagissant à `BidCreatedEvent` et `AnnouncementPublishedEvent`, et d'un scheduler `@Scheduled` pour la règle temporelle. Chaque déclenchement écrit une ligne dans `automation_history` (déjà existant, jamais écrit à ce jour) et respecte un plafond quotidien anti-emballement. Côté `dony-pro`, ajout de champs de configuration de seuils dans `PresetRuleCard.vue`.

**Tech Stack:** Spring Boot 3.4 (Java 21), Spring Events, `@Scheduled`, PostgreSQL/Flyway, JUnit 5 + Mockito côté back ; Vue 3.5 + vitest côté front.

## Global Constraints

- Aucune modification du comportement existant de `BidService.acceptBid`/`rejectBid` (chemin humain) — extraction additive uniquement, tests existants doivent rester verts sans modification.
- Toute action automatique doit écrire une ligne dans `automation_history` (succès ET échec), via `AutomationHistoryEntity`/`AutomationHistoryRepository` déjà existants (voir Task 2).
- Les seuils de preset sont stockés dans la colonne JSONB `action` de `AutomationRuleEntity` (pas de nouvelle colonne) — lus/écrits via la Map déjà exposée par `AutomationRuleService`.
- Plafond quotidien anti-emballement = constante `DAILY_ACTION_CAP = 20` (accept+reject confondus, par voyageur). Au-delà : désactivation de la règle fautive + notification, action NON exécutée.
- Cross-package : les listeners injectent `NotificationDispatcher` directement en sortie (pattern déjà établi dans le projet, ex. `PackageMatchTravelerNotifyListener`) — jamais `FcmService`/`SmsService` directement.
- `NotificationDispatcher.notifyCritical` est **privé** (réservé PAYMENT_RELEASED/DELIVERY_CONFIRMED/DISPUTE_OPENED) — toute notification d'automatisation utilise `notifyUser(...)` public (push + persistance, sans fallback SMS).
- Aucun `DELETE` physique — la table de suivi de capacité (`automation_capacity_watermarks`) utilise des mises à jour en place (`free_since` nullable), jamais de ligne supprimée.
- Migration Flyway : dernière existante `V169__recipient_city_optional.sql` → nouvelle migration `V170__automation_capacity_watermarks.sql`.
- Couverture ≥ 90 % sur tout code nouveau (CLAUDE.md), tests TDD stricts (RED avant GREEN).
- Jamais de `Co-Authored-By: Claude`, jamais de commit direct sur `main` — branche `feature/automation-engine` (déjà créée, spec déjà commitée dessus).

---

### Task 1: Méthodes système `acceptBidBySystem`/`rejectBidBySystem`

**Files:**
- Modify: `dony-back/src/main/java/com/dony/api/matching/BidService.java` (méthodes `acceptBid` lignes 457-511, `rejectBid` lignes 518-546)
- Test: `dony-back/src/test/java/com/dony/api/matching/BidServiceTest.java`

**Interfaces:**
- Produces: `BidResponse acceptBidBySystem(UUID bidId, UUID travelerId)` et `BidResponse rejectBidBySystem(UUID bidId, UUID travelerId, String reason)`, publics sur `BidService`, appelables par le futur `AutomationBidListener` (Task 3) sans `firebaseUid`.
- Consumes : rien de nouveau — réutilise `bidRepository`, `announcementRepository`, `userRepository`, `auditService`, `eventPublisher`, `requireBidStatus` déjà injectés/présents dans `BidService`.

- [ ] **Step 1: Lire le fichier actuel pour confirmer le corps exact avant extraction**

Ouvrir `BidService.java` autour des lignes 457-511 (`acceptBid`) et 518-546 (`rejectBid`). Noter les annotations exactes présentes (`@Transactional`, `@CacheEvict(value = "announcements-search", allEntries = true)` sur `acceptBid` ; `@Transactional`, `@CacheEvict(...)` sur `rejectBid` — vérifier au moment de l'implémentation, ne pas supposer).

- [ ] **Step 2: Écrire le test RED pour `acceptBidBySystem`**

Dans `BidServiceTest.java`, ajouter (adapter aux helpers de setup déjà présents dans la classe — `createTestAnnouncement`, `createTestBid`, etc., à identifier en lisant le début du fichier de test) :

```java
@Test
void acceptBidBySystem_acceptsWithoutFirebaseUid() {
    AnnouncementEntity announcement = createTestAnnouncement(travelerId); // helper existant, adapter le nom
    BidEntity bid = createTestBid(announcement.getId(), senderId, BidStatus.PAYMENT_ESCROWED);

    BidResponse response = bidService.acceptBidBySystem(bid.getId(), travelerId);

    assertThat(response.status()).isEqualTo(BidStatus.ACCEPTED.name());
    BidEntity reloaded = bidRepository.findById(bid.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(BidStatus.ACCEPTED);
}

@Test
void acceptBidBySystem_throwsWhenAnnouncementNotOwnedByTravelerId() {
    AnnouncementEntity announcement = createTestAnnouncement(travelerId);
    BidEntity bid = createTestBid(announcement.getId(), senderId, BidStatus.PAYMENT_ESCROWED);
    UUID otherTravelerId = UUID.randomUUID();

    assertThatThrownBy(() -> bidService.acceptBidBySystem(bid.getId(), otherTravelerId))
            .isInstanceOf(IllegalStateException.class);
}

@Test
void rejectBidBySystem_rejectsWithReason() {
    AnnouncementEntity announcement = createTestAnnouncement(travelerId);
    BidEntity bid = createTestBid(announcement.getId(), senderId, BidStatus.PAYMENT_ESCROWED);

    BidResponse response = bidService.rejectBidBySystem(bid.getId(), travelerId, "Poids trop important pour la capacité restante.");

    assertThat(response.status()).isEqualTo(BidStatus.REJECTED.name());
    BidEntity reloaded = bidRepository.findById(bid.getId()).orElseThrow();
    assertThat(reloaded.getRejectionReason()).isEqualTo("Poids trop important pour la capacité restante.");
}
```

Adapter les noms de helpers/assertions au style réel du fichier de test existant (lire les tests voisins de `acceptBid`/`rejectBid` déjà présents pour copier le pattern exact de setup — statut initial du bid, création d'annonce, etc.).

- [ ] **Step 2: Run tests → confirmer échec (méthodes n'existent pas encore)**

Run: `./mvnw test -Dtest=BidServiceTest#acceptBidBySystem_acceptsWithoutFirebaseUid,BidServiceTest#acceptBidBySystem_throwsWhenAnnouncementNotOwnedByTravelerId,BidServiceTest#rejectBidBySystem_rejectsWithReason`
Expected: FAIL — compilation error, méthodes inexistantes.

- [ ] **Step 3: Extraire la logique partagée et ajouter les méthodes système**

Dans `BidService.java`, remplacer le corps de `acceptBid` par un appel à une méthode privée partagée, et ajouter `acceptBidBySystem` :

```java
@Transactional
@CacheEvict(value = "announcements-search", allEntries = true)
public BidResponse acceptBid(UUID bidId, String firebaseUid) {
    BidEntity bid = bidRepository.findByIdForUpdate(bidId)
            .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                    "bid-not-found", "Bid Not Found", "Demande introuvable"));
    AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(bid.getAnnouncementId())
            .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                    "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));
    UserEntity traveler = findUserByFirebaseUid(firebaseUid);
    requireTravelerOwnsAnnouncement(traveler, announcement);
    return doAcceptBid(bid, announcement, traveler);
}

/**
 * Variante système (déclenchée par une automatisation) : le travelerId est déjà
 * garanti propriétaire par construction (résolu depuis la règle d'automatisation
 * elle-même), donc pas de firebaseUid à résoudre — juste une vérification défensive
 * d'appartenance.
 */
@Transactional
@CacheEvict(value = "announcements-search", allEntries = true)
public BidResponse acceptBidBySystem(UUID bidId, UUID travelerId) {
    BidEntity bid = bidRepository.findByIdForUpdate(bidId)
            .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                    "bid-not-found", "Bid Not Found", "Demande introuvable"));
    AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(bid.getAnnouncementId())
            .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                    "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));
    if (!announcement.getTravelerId().equals(travelerId)) {
        throw new IllegalStateException(
                "Automation travelerId mismatch for announcement " + announcement.getId());
    }
    UserEntity traveler = userRepository.findById(travelerId)
            .orElseThrow(() -> new IllegalStateException("Traveler not found: " + travelerId));
    return doAcceptBid(bid, announcement, traveler);
}

private BidResponse doAcceptBid(BidEntity bid, AnnouncementEntity announcement, UserEntity traveler) {
    requireBidStatus(bid, BidStatus.PAYMENT_ESCROWED);

    if (announcement.getStatus() == AnnouncementStatus.IN_PROGRESS
            || announcement.getStatus() == AnnouncementStatus.COMPLETED
            || announcement.getStatus() == AnnouncementStatus.CANCELLED) {
        throw new DonyBusinessException(HttpStatus.CONFLICT,
                "announcement-not-accepting", "Announcement Not Accepting",
                "Le voyageur est déjà parti, ce trajet n'accepte plus de colis");
    }

    boolean isKgFree = announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
    if (!isKgFree && bid.getWeightKg() != null
            && bid.getWeightKg().compareTo(announcement.getAvailableKg()) > 0) {
        throw new DonyBusinessException(
                HttpStatus.CONFLICT, "capacity-insufficient", "Insufficient Capacity",
                "Capacité insuffisante pour accepter cette demande");
    }

    bid.setStatus(BidStatus.ACCEPTED);
    if (bid.getQrToken() == null) {
        bid.setQrToken(UUID.randomUUID().toString());
    }
    if (bid.getTrackingNumber() == null) {
        bid.setTrackingNumber(generateTrackingNumber());
    }
    if (bid.getTrackingToken() == null) {
        bid.setTrackingToken(java.util.UUID.randomUUID().toString());
    }
    if (!isKgFree && bid.getWeightKg() != null) {
        announcement.setAvailableKg(announcement.getAvailableKg().subtract(bid.getWeightKg()));
    }
    if (!isKgFree && announcement.getAvailableKg().compareTo(BigDecimal.ZERO) <= 0) {
        announcement.setStatus(AnnouncementStatus.FULL);
    }
    announcementRepository.save(announcement);
    bid.applyHandoverFrom(announcement);
    bidRepository.save(bid);

    auditService.log("BID", bid.getId(), "BID_ACCEPTED", traveler.getId(),
            Map.<String, Object>of("announcementId", announcement.getId().toString(),
                   "weightKg", bid.getWeightKg() != null ? bid.getWeightKg().toString() : "null"));

    eventPublisher.publishEvent(new BidAcceptedEvent(
            bid.getId(), bid.getSenderId(), traveler.getId(), announcement.getId()));

    return toResponse(bid, userRepository.findById(bid.getSenderId()).orElse(null));
}
```

**Important** : vérifier au moment d'écrire ce code que le corps de `doAcceptBid` correspond EXACTEMENT au corps actuel de `acceptBid` lu au Step 1 (pas de divergence involontaire) — copier-coller depuis le fichier réel plutôt que retaper depuis ce plan si le fichier a changé depuis la rédaction de ce document.

Même pattern pour `rejectBid` :

```java
@Transactional
@CacheEvict(value = "announcements-search", allEntries = true)
public BidResponse rejectBid(UUID bidId, String firebaseUid, BidRejectRequest request) {
    BidEntity bid = findBid(bidId);
    AnnouncementEntity announcement = findAnnouncement(bid.getAnnouncementId());
    UserEntity traveler = findUserByFirebaseUid(firebaseUid);
    requireTravelerOwnsAnnouncement(traveler, announcement);
    return doRejectBid(bid, announcement, traveler, request);
}

@Transactional
@CacheEvict(value = "announcements-search", allEntries = true)
public BidResponse rejectBidBySystem(UUID bidId, UUID travelerId, String reason) {
    BidEntity bid = findBid(bidId);
    AnnouncementEntity announcement = findAnnouncement(bid.getAnnouncementId());
    if (!announcement.getTravelerId().equals(travelerId)) {
        throw new IllegalStateException(
                "Automation travelerId mismatch for announcement " + announcement.getId());
    }
    UserEntity traveler = userRepository.findById(travelerId)
            .orElseThrow(() -> new IllegalStateException("Traveler not found: " + travelerId));
    return doRejectBid(bid, announcement, traveler, new BidRejectRequest(reason));
}

private BidResponse doRejectBid(BidEntity bid, AnnouncementEntity announcement,
                                UserEntity traveler, BidRejectRequest request) {
    boolean isOffPlatformPending =
            (bid.getPaymentMethod() == PaymentMethod.CASH
             || bid.getPaymentMethod() == PaymentMethod.WAVE
             || bid.getPaymentMethod() == PaymentMethod.ORANGE_MONEY)
            && bid.getStatus() == BidStatus.PENDING;
    if (!isOffPlatformPending) {
        requireBidStatus(bid, BidStatus.PAYMENT_ESCROWED);
    }

    bid.setStatus(BidStatus.REJECTED);
    if (request != null) {
        bid.setRejectionReason(request.reason());
    }
    bidRepository.save(bid);

    auditService.log("BID", bid.getId(), "BID_REJECTED", traveler.getId(),
            Map.of("reason", String.valueOf(bid.getRejectionReason())));

    eventPublisher.publishEvent(new BidRejectedEvent(
            bid.getId(), bid.getSenderId(), bid.getRejectionReason()));

    return toResponse(bid, userRepository.findById(bid.getSenderId()).orElse(null));
}
```

- [ ] **Step 4: Run tous les tests `BidServiceTest` → GREEN**

Run: `./mvnw test -Dtest=BidServiceTest`
Expected: PASS intégral (les tests existants d'`acceptBid`/`rejectBid` doivent rester verts sans modification — preuve que l'extraction est comportementalement neutre).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dony/api/matching/BidService.java src/test/java/com/dony/api/matching/BidServiceTest.java
git commit -m "feat(automation): méthodes système acceptBidBySystem/rejectBidBySystem sur BidService"
```

---

### Task 2: `AutomationActionExecutor` — écriture historique + garde-fou quotidien

**Files:**
- Create: `dony-back/src/main/java/com/dony/api/automation/AutomationActionExecutor.java`
- Test: `dony-back/src/test/java/com/dony/api/automation/AutomationActionExecutorTest.java`

**Interfaces:**
- Consumes: `AutomationRuleRepository`, `AutomationHistoryRepository`, `AutomationRuleService.countTodayActions(UUID)` (déjà existant), `BidService.acceptBidBySystem`/`rejectBidBySystem` (Task 1), `NotificationDispatcher.notifyUser` (déjà existant).
- Produces: composant `@Service` `AutomationActionExecutor` avec les méthodes suivantes, consommées par les Tasks 3, 4, 5 :
  ```java
  boolean tryExecuteBidAction(AutomationRuleEntity rule, UUID travelerId, UUID bidId,
                              String actionTaken, java.util.function.Supplier<Void> action)
  void recordNotification(AutomationRuleEntity rule, UUID travelerId, String actionTaken)
  ```

- [ ] **Step 1: Écrire le test RED**

```java
package com.dony.api.automation;

import com.dony.api.common.DonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AutomationActionExecutorTest {

    @Mock private AutomationRuleRepository ruleRepository;
    @Mock private AutomationHistoryRepository historyRepository;
    @Mock private AutomationRuleService ruleService;

    private AutomationActionExecutor executor;
    private UUID travelerId;
    private UUID bidId;
    private AutomationRuleEntity rule;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        executor = new AutomationActionExecutor(ruleRepository, historyRepository, ruleService);
        travelerId = UUID.randomUUID();
        bidId = UUID.randomUUID();
        rule = new AutomationRuleEntity();
        rule.setTravelerId(travelerId);
        rule.setPresetRuleId("auto_accept_trusted");
        rule.setEnabled(true);
    }

    @Test
    void tryExecuteBidAction_runsActionAndWritesHistoryOnSuccess() {
        when(ruleService.countTodayActions(travelerId)).thenReturn(0L);

        boolean executed = executor.tryExecuteBidAction(rule, travelerId, bidId,
                "AUTO_ACCEPT", () -> null);

        assertThat(executed).isTrue();
        verify(historyRepository).save(argThat(h ->
                h.getTravelerId().equals(travelerId)
                && h.getBidId().equals(bidId)
                && h.getActionTaken().equals("AUTO_ACCEPT")
                && h.getResult().equals("SUCCESS")));
    }

    @Test
    void tryExecuteBidAction_writesFailureHistoryWhenActionThrows() {
        when(ruleService.countTodayActions(travelerId)).thenReturn(0L);

        boolean executed = executor.tryExecuteBidAction(rule, travelerId, bidId,
                "AUTO_ACCEPT", () -> { throw new DonyBusinessException(
                        org.springframework.http.HttpStatus.CONFLICT, "capacity-insufficient",
                        "x", "Capacité insuffisante"); });

        assertThat(executed).isFalse();
        verify(historyRepository).save(argThat(h ->
                h.getResult().equals("FAILURE")
                && h.getErrorDetail() != null));
    }

    @Test
    void tryExecuteBidAction_disablesRuleAndSkipsActionWhenDailyCapReached() {
        when(ruleService.countTodayActions(travelerId)).thenReturn(20L);

        boolean executed = executor.tryExecuteBidAction(rule, travelerId, bidId,
                "AUTO_ACCEPT", () -> { throw new AssertionError("action must not run"); });

        assertThat(executed).isFalse();
        assertThat(rule.isEnabled()).isFalse();
        verify(ruleRepository).save(rule);
        verify(historyRepository).save(argThat(h -> h.getResult().equals("CAP_REACHED")));
    }
}
```

- [ ] **Step 2: Run test → confirmer échec**

Run: `./mvnw test -Dtest=AutomationActionExecutorTest`
Expected: FAIL — compilation error, la classe n'existe pas.

- [ ] **Step 3: Implémenter `AutomationActionExecutor`**

```java
package com.dony.api.automation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class AutomationActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(AutomationActionExecutor.class);
    static final long DAILY_ACTION_CAP = 20;

    private final AutomationRuleRepository ruleRepository;
    private final AutomationHistoryRepository historyRepository;
    private final AutomationRuleService ruleService;

    public AutomationActionExecutor(AutomationRuleRepository ruleRepository,
                                    AutomationHistoryRepository historyRepository,
                                    AutomationRuleService ruleService) {
        this.ruleRepository = ruleRepository;
        this.historyRepository = historyRepository;
        this.ruleService = ruleService;
    }

    /**
     * Exécute une action liée à un bid (accept/reject) si le plafond quotidien
     * n'est pas atteint, en écrivant systématiquement une ligne d'historique.
     * Retourne true si l'action a été exécutée avec succès.
     */
    public boolean tryExecuteBidAction(AutomationRuleEntity rule, UUID travelerId, UUID bidId,
                                       String actionTaken, Supplier<Void> action) {
        if (ruleService.countTodayActions(travelerId) >= DAILY_ACTION_CAP) {
            rule.setEnabled(false);
            ruleRepository.save(rule);
            writeHistory(rule, travelerId, bidId, null, actionTaken, "CAP_REACHED",
                    "Plafond quotidien de " + DAILY_ACTION_CAP + " actions atteint — règle désactivée.");
            log.warn("Automation daily cap reached for traveler {}, rule {} disabled",
                    travelerId, rule.getPresetRuleId());
            return false;
        }

        try {
            action.get();
            writeHistory(rule, travelerId, bidId, null, actionTaken, "SUCCESS", null);
            return true;
        } catch (Exception e) {
            writeHistory(rule, travelerId, bidId, null, actionTaken, "FAILURE", e.getMessage());
            log.warn("Automation action {} failed for bid {}: {}", actionTaken, bidId, e.getMessage());
            return false;
        }
    }

    /** Enregistre une notification déclenchée par une règle (pas d'action bid associée). */
    public void recordNotification(AutomationRuleEntity rule, UUID travelerId, String actionTaken) {
        writeHistory(rule, travelerId, null, null, actionTaken, "SUCCESS", null);
    }

    private void writeHistory(AutomationRuleEntity rule, UUID travelerId, UUID bidId, UUID tripId,
                              String actionTaken, String result, String errorDetail) {
        AutomationHistoryEntity history = new AutomationHistoryEntity();
        history.setTravelerId(travelerId);
        history.setRuleId(rule.getId());
        history.setRuleLabel(rule.getName() != null ? rule.getName() : rule.getPresetRuleId());
        history.setBidId(bidId);
        history.setTripId(tripId);
        history.setActionTaken(actionTaken);
        history.setResult(result);
        history.setErrorDetail(errorDetail);
        history.setTriggeredAt(LocalDateTime.now(ZoneOffset.UTC));
        historyRepository.save(history);
    }
}
```

- [ ] **Step 4: Run test → GREEN**

Run: `./mvnw test -Dtest=AutomationActionExecutorTest`
Expected: PASS (3/3).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dony/api/automation/AutomationActionExecutor.java src/test/java/com/dony/api/automation/AutomationActionExecutorTest.java
git commit -m "feat(automation): AutomationActionExecutor — historique systématique + plafond quotidien"
```

---

### Task 3: `AutomationBidListener` — règles 1 (accepter confiance), 2 (refuser trop lourd), 6 (dernière minute)

**Files:**
- Create: `dony-back/src/main/java/com/dony/api/automation/AutomationBidListener.java`
- Test: `dony-back/src/test/java/com/dony/api/automation/AutomationBidListenerTest.java`

**Interfaces:**
- Consumes: `BidCreatedEvent` (`bidId, announcementId, travelerId, senderId, senderFirstName, weightKg, corridor`), `AutomationRuleRepository.findByTravelerIdOrderByCreatedAtAsc`, `BidService.acceptBidBySystem`/`rejectBidBySystem` (Task 1), `AutomationActionExecutor` (Task 2), `UserRepository.findById` (pour `averageRating`), `BidRepository.findById` + `AnnouncementRepository.findById` (pour relire poids/capacité/departureAt), `NotificationDispatcher.notifyUser`.
- Produces: `@Component AutomationBidListener` avec `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` sur `onBidCreated(BidCreatedEvent event)`.

- [ ] **Step 1: Écrire le test RED (priorité refus > accept, et règle 6 indépendante)**

```java
package com.dony.api.automation;

import com.dony.api.matching.BidService;
import com.dony.api.matching.events.BidCreatedEvent;
import com.dony.api.notifications.NotificationDispatcher;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AutomationBidListenerTest {

    @Mock private AutomationRuleRepository ruleRepository;
    @Mock private AutomationActionExecutor executor;
    @Mock private BidService bidService;
    @Mock private UserRepository userRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private NotificationDispatcher notificationDispatcher;

    private AutomationBidListener listener;
    private UUID travelerId, bidId, announcementId, senderId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new AutomationBidListener(ruleRepository, executor, bidService,
                userRepository, announcementRepository, notificationDispatcher);
        travelerId = UUID.randomUUID();
        bidId = UUID.randomUUID();
        announcementId = UUID.randomUUID();
        senderId = UUID.randomUUID();
        when(executor.tryExecuteBidAction(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> { ((java.util.function.Supplier<?>) inv.getArgument(4)).get(); return true; });
    }

    private AutomationRuleEntity presetRule(String presetId, boolean enabled, Map<String, Object> action) {
        AutomationRuleEntity r = new AutomationRuleEntity();
        r.setTravelerId(travelerId);
        r.setRuleType("PRESET");
        r.setPresetRuleId(presetId);
        r.setEnabled(enabled);
        r.setAction(action);
        return r;
    }

    @Test
    void onBidCreated_rejectsOverweight_evenIfAcceptTrustedAlsoEnabled() {
        UserEntity sender = new UserEntity();
        sender.setId(senderId);
        sender.setAverageRating(new BigDecimal("4.9"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("5"));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("auto_reject_overweight", true, Map.of()),
                presetRule("auto_accept_trusted", true, Map.of("minRating", 4.0))
        ));

        BidCreatedEvent event = new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("10"), "Paris → Dakar");
        listener.onBidCreated(event);

        verify(bidService).rejectBidBySystem(eq(bidId), eq(travelerId), any());
        verify(bidService, never()).acceptBidBySystem(any(), any());
    }

    @Test
    void onBidCreated_acceptsTrustedSenderWhenRatingAboveThreshold() {
        UserEntity sender = new UserEntity();
        sender.setId(senderId);
        sender.setAverageRating(new BigDecimal("4.8"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("20"));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("auto_accept_trusted", true, Map.of("minRating", 4.0))
        ));

        BidCreatedEvent event = new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("5"), "Paris → Dakar");
        listener.onBidCreated(event);

        verify(bidService).acceptBidBySystem(bidId, travelerId);
    }

    @Test
    void onBidCreated_doesNothingWhenNoRuleMatches() {
        UserEntity sender = new UserEntity();
        sender.setId(senderId);
        sender.setAverageRating(new BigDecimal("3.0"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("20"));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("auto_accept_trusted", true, Map.of("minRating", 4.0))
        ));

        BidCreatedEvent event = new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("5"), "Paris → Dakar");
        listener.onBidCreated(event);

        verify(bidService, never()).acceptBidBySystem(any(), any());
        verify(bidService, never()).rejectBidBySystem(any(), any(), any());
    }

    @Test
    void onBidCreated_notifiesLastMinuteWhenDepartureWithinConfiguredHours() {
        UserEntity sender = new UserEntity();
        sender.setId(senderId);
        sender.setAverageRating(new BigDecimal("3.0"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("20"));
        announcement.setDepartureAt(OffsetDateTime.now().plusHours(10));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("alert_last_minute_bid", true, Map.of("hoursBeforeDeparture", 48))
        ));

        BidCreatedEvent event = new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("5"), "Paris → Dakar");
        listener.onBidCreated(event);

        verify(notificationDispatcher).notifyUser(eq(travelerId), any(), any(), any());
    }

    @Test
    void onBidCreated_doesNotNotifyLastMinuteWhenDepartureFarAway() {
        UserEntity sender = new UserEntity();
        sender.setId(senderId);
        sender.setAverageRating(new BigDecimal("3.0"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("20"));
        announcement.setDepartureAt(OffsetDateTime.now().plusDays(10));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("alert_last_minute_bid", true, Map.of("hoursBeforeDeparture", 48))
        ));

        BidCreatedEvent event = new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("5"), "Paris → Dakar");
        listener.onBidCreated(event);

        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
    }
}
```

- [ ] **Step 2: Run test → confirmer échec**

Run: `./mvnw test -Dtest=AutomationBidListenerTest`
Expected: FAIL — la classe `AutomationBidListener` n'existe pas.

- [ ] **Step 3: Implémenter `AutomationBidListener`**

```java
package com.dony.api.automation;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidService;
import com.dony.api.matching.events.BidCreatedEvent;
import com.dony.api.notifications.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Écoute BidCreatedEvent (publié une fois le paiement de l'expéditeur autorisé,
 * bid en PAYMENT_ESCROWED) et exécute les règles d'automatisation actives du
 * voyageur propriétaire de l'annonce : refus auto (priorité), acceptation auto,
 * alerte dernière minute.
 */
@Component
public class AutomationBidListener {

    private static final Logger log = LoggerFactory.getLogger(AutomationBidListener.class);

    private final AutomationRuleRepository ruleRepository;
    private final AutomationActionExecutor executor;
    private final BidService bidService;
    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;
    private final NotificationDispatcher notificationDispatcher;

    public AutomationBidListener(AutomationRuleRepository ruleRepository,
                                 AutomationActionExecutor executor,
                                 BidService bidService,
                                 UserRepository userRepository,
                                 AnnouncementRepository announcementRepository,
                                 NotificationDispatcher notificationDispatcher) {
        this.ruleRepository = ruleRepository;
        this.executor = executor;
        this.bidService = bidService;
        this.userRepository = userRepository;
        this.announcementRepository = announcementRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBidCreated(BidCreatedEvent event) {
        List<AutomationRuleEntity> rules =
                ruleRepository.findByTravelerIdOrderByCreatedAtAsc(event.getTravelerId());

        Optional<AutomationRuleEntity> rejectRule = findEnabledPreset(rules, "auto_reject_overweight");
        Optional<AutomationRuleEntity> acceptRule = findEnabledPreset(rules, "auto_accept_trusted");
        Optional<AutomationRuleEntity> lastMinuteRule = findEnabledPreset(rules, "alert_last_minute_bid");

        AnnouncementEntity announcement = announcementRepository.findById(event.getAnnouncementId())
                .orElse(null);
        if (announcement == null) {
            log.warn("Automation: announcement {} not found for bid {}", event.getAnnouncementId(), event.getBidId());
            return;
        }

        boolean rejected = false;
        if (rejectRule.isPresent() && event.getWeightKg() != null
                && event.getWeightKg().compareTo(announcement.getAvailableKg()) > 0) {
            AutomationRuleEntity rule = rejectRule.get();
            rejected = executor.tryExecuteBidAction(rule, event.getTravelerId(), event.getBidId(),
                    "AUTO_REJECT_OVERWEIGHT", () -> {
                        bidService.rejectBidBySystem(event.getBidId(), event.getTravelerId(),
                                "Le poids de ce colis dépasse la capacité restante sur ce trajet.");
                        return null;
                    });
        }

        if (!rejected && acceptRule.isPresent()) {
            AutomationRuleEntity rule = acceptRule.get();
            BigDecimal minRating = configNumber(rule, "minRating", new BigDecimal("4.0"));
            UserEntity sender = userRepository.findById(event.getSenderId()).orElse(null);
            boolean weightOk = event.getWeightKg() == null
                    || event.getWeightKg().compareTo(announcement.getAvailableKg()) <= 0;
            boolean ratingOk = sender != null && sender.getAverageRating() != null
                    && sender.getAverageRating().compareTo(minRating) >= 0;
            if (weightOk && ratingOk) {
                executor.tryExecuteBidAction(rule, event.getTravelerId(), event.getBidId(),
                        "AUTO_ACCEPT_TRUSTED", () -> {
                            bidService.acceptBidBySystem(event.getBidId(), event.getTravelerId());
                            return null;
                        });
            }
        }

        if (lastMinuteRule.isPresent() && announcement.getDepartureAt() != null) {
            AutomationRuleEntity rule = lastMinuteRule.get();
            int hoursBeforeDeparture = configInt(rule, "hoursBeforeDeparture", 48);
            long hoursUntilDeparture = java.time.Duration.between(
                    OffsetDateTime.now(), announcement.getDepartureAt()).toHours();
            if (hoursUntilDeparture >= 0 && hoursUntilDeparture < hoursBeforeDeparture) {
                notificationDispatcher.notifyUser(event.getTravelerId(),
                        "Offre de dernière minute",
                        "Une offre vient d'arriver pour un départ dans moins de "
                                + hoursBeforeDeparture + "h (" + event.getCorridor() + ").",
                        Map.of("type", "automation_last_minute", "bidId", event.getBidId().toString()));
                executor.recordNotification(rule, event.getTravelerId(), "ALERT_LAST_MINUTE_BID");
            }
        }
    }

    private Optional<AutomationRuleEntity> findEnabledPreset(List<AutomationRuleEntity> rules, String presetId) {
        return rules.stream()
                .filter(r -> presetId.equals(r.getPresetRuleId()) && r.isEnabled())
                .findFirst();
    }

    private BigDecimal configNumber(AutomationRuleEntity rule, String key, BigDecimal fallback) {
        Object v = rule.getAction() != null ? rule.getAction().get(key) : null;
        if (v == null) return fallback;
        return new BigDecimal(v.toString());
    }

    private int configInt(AutomationRuleEntity rule, String key, int fallback) {
        Object v = rule.getAction() != null ? rule.getAction().get(key) : null;
        if (v == null) return fallback;
        return Integer.parseInt(v.toString());
    }
}
```

- [ ] **Step 4: Run test → GREEN**

Run: `./mvnw test -Dtest=AutomationBidListenerTest`
Expected: PASS (5/5).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dony/api/automation/AutomationBidListener.java src/test/java/com/dony/api/automation/AutomationBidListenerTest.java
git commit -m "feat(automation): AutomationBidListener — accepter confiance, refuser trop lourd, alerte dernière minute"
```

---

### Task 4: Migration watermark + `CapacityWatchScheduler` (règle 4)

**Files:**
- Create: `dony-back/src/main/resources/db/migration/V170__automation_capacity_watermarks.sql`
- Create: `dony-back/src/main/java/com/dony/api/automation/AutomationCapacityWatermarkEntity.java`
- Create: `dony-back/src/main/java/com/dony/api/automation/AutomationCapacityWatermarkRepository.java`
- Create: `dony-back/src/main/java/com/dony/api/automation/CapacityWatchScheduler.java`
- Test: `dony-back/src/test/java/com/dony/api/automation/CapacityWatchSchedulerTest.java`

**Interfaces:**
- Consumes: `AutomationRuleRepository`, `AnnouncementRepository.findActiveByTravelerId(UUID)` (déjà existant), `AutomationCapacityWatermarkRepository` (nouveau), `NotificationDispatcher.notifyUser`, `AutomationActionExecutor.recordNotification`.
- Produces: table `automation_capacity_watermarks`, entité/repo associés, `@Component CapacityWatchScheduler` avec `@Scheduled(fixedRate = 15 * 60 * 1000)`.

- [ ] **Step 1: Migration SQL**

```sql
-- V170__automation_capacity_watermarks.sql
CREATE TABLE automation_capacity_watermarks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    announcement_id UUID NOT NULL UNIQUE REFERENCES announcements(id),
    free_since TIMESTAMP WITH TIME ZONE,
    last_alerted_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: Entité + repository**

```java
// AutomationCapacityWatermarkEntity.java
package com.dony.api.automation;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "automation_capacity_watermarks")
public class AutomationCapacityWatermarkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "announcement_id", nullable = false, unique = true)
    private UUID announcementId;

    @Column(name = "free_since")
    private OffsetDateTime freeSince;

    @Column(name = "last_alerted_at")
    private OffsetDateTime lastAlertedAt;

    public AutomationCapacityWatermarkEntity() {}

    public UUID getId() { return id; }
    public UUID getAnnouncementId() { return announcementId; }
    public void setAnnouncementId(UUID announcementId) { this.announcementId = announcementId; }
    public OffsetDateTime getFreeSince() { return freeSince; }
    public void setFreeSince(OffsetDateTime freeSince) { this.freeSince = freeSince; }
    public OffsetDateTime getLastAlertedAt() { return lastAlertedAt; }
    public void setLastAlertedAt(OffsetDateTime lastAlertedAt) { this.lastAlertedAt = lastAlertedAt; }
}
```

```java
// AutomationCapacityWatermarkRepository.java
package com.dony.api.automation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AutomationCapacityWatermarkRepository
        extends JpaRepository<AutomationCapacityWatermarkEntity, UUID> {

    Optional<AutomationCapacityWatermarkEntity> findByAnnouncementId(UUID announcementId);
}
```

- [ ] **Step 3: Écrire le test RED du scheduler**

```java
package com.dony.api.automation;

import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CapacityWatchSchedulerTest {

    @Mock private AutomationRuleRepository ruleRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AutomationCapacityWatermarkRepository watermarkRepository;
    @Mock private NotificationDispatcher notificationDispatcher;
    @Mock private AutomationActionExecutor executor;

    private CapacityWatchScheduler scheduler;
    private UUID travelerId, announcementId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        scheduler = new CapacityWatchScheduler(ruleRepository, announcementRepository,
                watermarkRepository, notificationDispatcher, executor);
        travelerId = UUID.randomUUID();
        announcementId = UUID.randomUUID();
    }

    private AutomationRuleEntity capacityRule(Map<String, Object> action) {
        AutomationRuleEntity r = new AutomationRuleEntity();
        r.setTravelerId(travelerId);
        r.setPresetRuleId("alert_capacity_free");
        r.setEnabled(true);
        r.setAction(action);
        return r;
    }

    private AnnouncementEntity announcement(BigDecimal availableKg) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setAvailableKg(availableKg);
        try {
            var idField = AnnouncementEntity.class.getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(a, announcementId);
        } catch (Exception ignored) {}
        return a;
    }

    @Test
    void run_createsWatermarkOnFirstObservationAboveThreshold_withoutNotifying() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(capacityRule(Map.of("freedKgThreshold", 5, "consecutiveHours", 2))));
        when(announcementRepository.findActiveByTravelerId(travelerId))
                .thenReturn(List.of(announcement(new BigDecimal("10"))));
        when(watermarkRepository.findByAnnouncementId(announcementId)).thenReturn(Optional.empty());

        scheduler.checkCapacityWatermarks(List.of(travelerId));

        verify(watermarkRepository).save(argThat(w -> w.getFreeSince() != null));
        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
    }

    @Test
    void run_notifiesWhenThresholdHeldLongEnough() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(capacityRule(Map.of("freedKgThreshold", 5, "consecutiveHours", 2))));
        when(announcementRepository.findActiveByTravelerId(travelerId))
                .thenReturn(List.of(announcement(new BigDecimal("10"))));

        AutomationCapacityWatermarkEntity existing = new AutomationCapacityWatermarkEntity();
        existing.setAnnouncementId(announcementId);
        existing.setFreeSince(OffsetDateTime.now().minusHours(3));
        when(watermarkRepository.findByAnnouncementId(announcementId)).thenReturn(Optional.of(existing));

        scheduler.checkCapacityWatermarks(List.of(travelerId));

        verify(notificationDispatcher).notifyUser(eq(travelerId), any(), any(), any());
        verify(watermarkRepository).save(argThat(w -> w.getLastAlertedAt() != null));
    }

    @Test
    void run_doesNotReNotifyBeforeNextFreeWindow() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(capacityRule(Map.of("freedKgThreshold", 5, "consecutiveHours", 2))));
        when(announcementRepository.findActiveByTravelerId(travelerId))
                .thenReturn(List.of(announcement(new BigDecimal("10"))));

        OffsetDateTime freeSince = OffsetDateTime.now().minusHours(3);
        AutomationCapacityWatermarkEntity existing = new AutomationCapacityWatermarkEntity();
        existing.setAnnouncementId(announcementId);
        existing.setFreeSince(freeSince);
        existing.setLastAlertedAt(freeSince.plusMinutes(1));
        when(watermarkRepository.findByAnnouncementId(announcementId)).thenReturn(Optional.of(existing));

        scheduler.checkCapacityWatermarks(List.of(travelerId));

        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
    }

    @Test
    void run_resetsWatermarkWhenCapacityDropsBelowThreshold() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(capacityRule(Map.of("freedKgThreshold", 5, "consecutiveHours", 2))));
        when(announcementRepository.findActiveByTravelerId(travelerId))
                .thenReturn(List.of(announcement(new BigDecimal("2"))));

        AutomationCapacityWatermarkEntity existing = new AutomationCapacityWatermarkEntity();
        existing.setAnnouncementId(announcementId);
        existing.setFreeSince(OffsetDateTime.now().minusHours(3));
        when(watermarkRepository.findByAnnouncementId(announcementId)).thenReturn(Optional.of(existing));

        scheduler.checkCapacityWatermarks(List.of(travelerId));

        verify(watermarkRepository).save(argThat(w ->
                w.getFreeSince() == null && w.getLastAlertedAt() == null));
    }
}
```

- [ ] **Step 4: Run test → confirmer échec**

Run: `./mvnw test -Dtest=CapacityWatchSchedulerTest`
Expected: FAIL — classes inexistantes.

- [ ] **Step 5: Implémenter `CapacityWatchScheduler`**

```java
package com.dony.api.automation;

import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.notifications.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Toutes les 15 minutes, vérifie pour chaque voyageur ayant la règle
 * "alert_capacity_free" active si l'une de ses annonces a retrouvé assez de
 * capacité (freedKgThreshold) depuis assez longtemps (consecutiveHours), et
 * notifie une seule fois par fenêtre de libération.
 */
@Component
public class CapacityWatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(CapacityWatchScheduler.class);

    private final AutomationRuleRepository ruleRepository;
    private final AnnouncementRepository announcementRepository;
    private final AutomationCapacityWatermarkRepository watermarkRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final AutomationActionExecutor executor;

    public CapacityWatchScheduler(AutomationRuleRepository ruleRepository,
                                  AnnouncementRepository announcementRepository,
                                  AutomationCapacityWatermarkRepository watermarkRepository,
                                  NotificationDispatcher notificationDispatcher,
                                  AutomationActionExecutor executor) {
        this.ruleRepository = ruleRepository;
        this.announcementRepository = announcementRepository;
        this.watermarkRepository = watermarkRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.executor = executor;
    }

    @Scheduled(fixedRate = 15 * 60 * 1000)
    @Transactional
    public void run() {
        List<UUID> travelerIds = ruleRepository.findAll().stream()
                .filter(r -> "alert_capacity_free".equals(r.getPresetRuleId()) && r.isEnabled())
                .map(AutomationRuleEntity::getTravelerId)
                .distinct()
                .toList();
        checkCapacityWatermarks(travelerIds);
    }

    void checkCapacityWatermarks(List<UUID> travelerIds) {
        for (UUID travelerId : travelerIds) {
            AutomationRuleEntity rule = ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)
                    .stream()
                    .filter(r -> "alert_capacity_free".equals(r.getPresetRuleId()) && r.isEnabled())
                    .findFirst()
                    .orElse(null);
            if (rule == null) continue;

            BigDecimal freedKgThreshold = configNumber(rule, "freedKgThreshold", new BigDecimal("5"));
            int consecutiveHours = configInt(rule, "consecutiveHours", 2);

            for (AnnouncementEntity announcement : announcementRepository.findActiveByTravelerId(travelerId)) {
                evaluateAnnouncement(rule, announcement, freedKgThreshold, consecutiveHours);
            }
        }
    }

    private void evaluateAnnouncement(AutomationRuleEntity rule, AnnouncementEntity announcement,
                                      BigDecimal freedKgThreshold, int consecutiveHours) {
        UUID announcementId = announcement.getId();
        AutomationCapacityWatermarkEntity watermark = watermarkRepository.findByAnnouncementId(announcementId)
                .orElseGet(() -> {
                    AutomationCapacityWatermarkEntity w = new AutomationCapacityWatermarkEntity();
                    w.setAnnouncementId(announcementId);
                    return w;
                });

        boolean aboveThreshold = announcement.getAvailableKg() != null
                && announcement.getAvailableKg().compareTo(freedKgThreshold) >= 0;

        if (!aboveThreshold) {
            if (watermark.getFreeSince() != null || watermark.getLastAlertedAt() != null) {
                watermark.setFreeSince(null);
                watermark.setLastAlertedAt(null);
                watermarkRepository.save(watermark);
            }
            return;
        }

        if (watermark.getFreeSince() == null) {
            watermark.setFreeSince(OffsetDateTime.now());
            watermarkRepository.save(watermark);
            return;
        }

        boolean heldLongEnough =
                Duration.between(watermark.getFreeSince(), OffsetDateTime.now()).toHours() >= consecutiveHours;
        boolean alreadyAlertedThisWindow =
                watermark.getLastAlertedAt() != null && !watermark.getLastAlertedAt().isBefore(watermark.getFreeSince());

        if (heldLongEnough && !alreadyAlertedThisWindow) {
            notificationDispatcher.notifyUser(rule.getTravelerId(),
                    "De la capacité s'est libérée",
                    "Tu as retrouvé " + announcement.getAvailableKg() + " kg de disponible depuis plus de "
                            + consecutiveHours + "h sur " + announcement.getDepartureCity()
                            + " → " + announcement.getArrivalCity() + ".",
                    Map.of("type", "automation_capacity_free", "announcementId", announcementId.toString()));
            watermark.setLastAlertedAt(OffsetDateTime.now());
            watermarkRepository.save(watermark);
            executor.recordNotification(rule, rule.getTravelerId(), "ALERT_CAPACITY_FREE");
        }
    }

    private BigDecimal configNumber(AutomationRuleEntity rule, String key, BigDecimal fallback) {
        Object v = rule.getAction() != null ? rule.getAction().get(key) : null;
        if (v == null) return fallback;
        return new BigDecimal(v.toString());
    }

    private int configInt(AutomationRuleEntity rule, String key, int fallback) {
        Object v = rule.getAction() != null ? rule.getAction().get(key) : null;
        if (v == null) return fallback;
        return Integer.parseInt(v.toString());
    }
}
```

**Note pour l'implémenteur** : `AnnouncementRepository.findActiveByTravelerId` retourne déjà les annonces `ACTIVE` (voir signature ligne 139 du repository) — vérifier au moment d'implémenter que cette méthode couvre le besoin (annonces avec de la capacité affichable), l'adapter si son filtre exact diffère de "capacité potentiellement libre à surveiller".

- [ ] **Step 6: Run migration + tests → GREEN**

Run: `./mvnw flyway:migrate` puis `./mvnw test -Dtest=CapacityWatchSchedulerTest`
Expected: migration appliquée sans erreur, 4/4 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V170__automation_capacity_watermarks.sql \
        src/main/java/com/dony/api/automation/AutomationCapacityWatermarkEntity.java \
        src/main/java/com/dony/api/automation/AutomationCapacityWatermarkRepository.java \
        src/main/java/com/dony/api/automation/CapacityWatchScheduler.java \
        src/test/java/com/dony/api/automation/CapacityWatchSchedulerTest.java
git commit -m "feat(automation): CapacityWatchScheduler — alerte capacité libérée (règle 4)"
```

---

### Task 5: `AutomationAnnouncementListener` — règle 5 (expéditeurs fidèles)

**Files:**
- Modify: `dony-back/src/main/java/com/dony/api/matching/BidRepository.java` (nouvelle query)
- Create: `dony-back/src/main/java/com/dony/api/automation/AutomationAnnouncementListener.java`
- Test: `dony-back/src/test/java/com/dony/api/automation/AutomationAnnouncementListenerTest.java`

**Interfaces:**
- Produces: `List<UUID> BidRepository.findLoyalSenderIds(UUID travelerId, String departureCity, String arrivalCity)`.
- Consumes: `AnnouncementPublishedEvent` (`announcementId, travelerId, travelerName, departureCity, arrivalCity`), `AutomationRuleRepository`, `NotificationDispatcher.notifyUser`, `AutomationActionExecutor.recordNotification`.

- [ ] **Step 1: Ajouter la query dans `BidRepository`**

```java
@Query("SELECT DISTINCT b.senderId FROM BidEntity b " +
       "JOIN AnnouncementEntity a ON a.id = b.announcementId " +
       "WHERE a.travelerId = :travelerId AND a.departureCity = :departureCity " +
       "AND a.arrivalCity = :arrivalCity AND b.status = com.dony.api.matching.BidStatus.ACCEPTED " +
       "AND b.deletedAt IS NULL")
List<UUID> findLoyalSenderIds(@Param("travelerId") UUID travelerId,
                              @Param("departureCity") String departureCity,
                              @Param("arrivalCity") String arrivalCity);
```

- [ ] **Step 2: Écrire le test RED**

```java
package com.dony.api.automation;

import com.dony.api.matching.AnnouncementPublishedEvent;
import com.dony.api.matching.BidRepository;
import com.dony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AutomationAnnouncementListenerTest {

    @Mock private AutomationRuleRepository ruleRepository;
    @Mock private BidRepository bidRepository;
    @Mock private NotificationDispatcher notificationDispatcher;
    @Mock private AutomationActionExecutor executor;

    private AutomationAnnouncementListener listener;
    private UUID travelerId, senderId1, senderId2, announcementId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new AutomationAnnouncementListener(ruleRepository, bidRepository,
                notificationDispatcher, executor);
        travelerId = UUID.randomUUID();
        senderId1 = UUID.randomUUID();
        senderId2 = UUID.randomUUID();
        announcementId = UUID.randomUUID();
    }

    private AutomationRuleEntity loyalRule(boolean enabled) {
        AutomationRuleEntity r = new AutomationRuleEntity();
        r.setTravelerId(travelerId);
        r.setPresetRuleId("notify_loyal_senders");
        r.setEnabled(enabled);
        r.setAction(Map.of());
        return r;
    }

    @Test
    void onAnnouncementPublished_notifiesEachLoyalSenderWhenRuleEnabled() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(loyalRule(true)));
        when(bidRepository.findLoyalSenderIds(travelerId, "Paris", "Dakar"))
                .thenReturn(List.of(senderId1, senderId2));

        listener.onAnnouncementPublished(new AnnouncementPublishedEvent(
                announcementId, travelerId, "Jean", "Paris", "Dakar"));

        verify(notificationDispatcher).notifyUser(eq(senderId1), any(), any(), any());
        verify(notificationDispatcher).notifyUser(eq(senderId2), any(), any(), any());
    }

    @Test
    void onAnnouncementPublished_doesNothingWhenRuleDisabled() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(loyalRule(false)));

        listener.onAnnouncementPublished(new AnnouncementPublishedEvent(
                announcementId, travelerId, "Jean", "Paris", "Dakar"));

        verify(bidRepository, never()).findLoyalSenderIds(any(), any(), any());
        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
    }

    @Test
    void onAnnouncementPublished_doesNothingWhenNoLoyalSenders() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(loyalRule(true)));
        when(bidRepository.findLoyalSenderIds(travelerId, "Paris", "Dakar"))
                .thenReturn(List.of());

        listener.onAnnouncementPublished(new AnnouncementPublishedEvent(
                announcementId, travelerId, "Jean", "Paris", "Dakar"));

        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
    }
}
```

- [ ] **Step 3: Run test → confirmer échec**

Run: `./mvnw test -Dtest=AutomationAnnouncementListenerTest`
Expected: FAIL — classe inexistante.

- [ ] **Step 4: Implémenter `AutomationAnnouncementListener`**

```java
package com.dony.api.automation;

import com.dony.api.matching.AnnouncementPublishedEvent;
import com.dony.api.matching.BidRepository;
import com.dony.api.notifications.NotificationDispatcher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Écoute AnnouncementPublishedEvent : si le voyageur a activé "notify_loyal_senders",
 * notifie chaque expéditeur ayant déjà eu un bid ACCEPTED avec lui sur ce corridor.
 */
@Component
public class AutomationAnnouncementListener {

    private final AutomationRuleRepository ruleRepository;
    private final BidRepository bidRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final AutomationActionExecutor executor;

    public AutomationAnnouncementListener(AutomationRuleRepository ruleRepository,
                                          BidRepository bidRepository,
                                          NotificationDispatcher notificationDispatcher,
                                          AutomationActionExecutor executor) {
        this.ruleRepository = ruleRepository;
        this.bidRepository = bidRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnnouncementPublished(AnnouncementPublishedEvent event) {
        AutomationRuleEntity rule = ruleRepository
                .findByTravelerIdOrderByCreatedAtAsc(event.travelerId())
                .stream()
                .filter(r -> "notify_loyal_senders".equals(r.getPresetRuleId()) && r.isEnabled())
                .findFirst()
                .orElse(null);
        if (rule == null) return;

        List<UUID> loyalSenderIds = bidRepository.findLoyalSenderIds(
                event.travelerId(), event.departureCity(), event.arrivalCity());
        if (loyalSenderIds.isEmpty()) return;

        String corridor = event.departureCity() + " → " + event.arrivalCity();
        for (UUID senderId : loyalSenderIds) {
            notificationDispatcher.notifyUser(senderId,
                    "Nouveau trajet sur votre corridor habituel",
                    event.travelerName() + " vient de publier un nouveau trajet " + corridor + ".",
                    Map.of("type", "automation_loyal_sender", "announcementId", event.announcementId().toString()));
        }
        executor.recordNotification(rule, event.travelerId(), "NOTIFY_LOYAL_SENDERS");
    }
}
```

- [ ] **Step 5: Run test → GREEN**

Run: `./mvnw test -Dtest=AutomationAnnouncementListenerTest`
Expected: PASS (3/3).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/dony/api/matching/BidRepository.java \
        src/main/java/com/dony/api/automation/AutomationAnnouncementListener.java \
        src/test/java/com/dony/api/automation/AutomationAnnouncementListenerTest.java
git commit -m "feat(automation): AutomationAnnouncementListener — notifier expéditeurs fidèles (règle 5)"
```

---

### Task 6: UI de configuration des seuils (dony-pro)

**Files:**
- Modify: `dony-pro/app/features/automations/types/index.ts`
- Modify: `dony-pro/app/features/automations/composables/useAutomations.ts`
- Modify: `dony-pro/app/features/automations/components/PresetRuleCard.vue`
- Test: `dony-pro/tests/unit/features/automations/PresetRuleCard.spec.ts` (créer si absent), `dony-pro/tests/unit/features/automations/useAutomations.spec.ts` (créer si absent — vérifier d'abord s'il existe déjà un test composable pour ce module)

**Interfaces:**
- Consumes: `automationsService.updateRule(id, payload)` (déjà existant, accepte déjà `config`).
- Produces: `useAutomations().updatePresetConfig(id: string, config: PresetRuleConfig): Promise<void>`.

- [ ] **Step 1: Vérifier l'existant avant d'écrire les tests**

Lire `dony-pro/tests/unit/features/automations/` (s'il existe) pour identifier le pattern de test déjà utilisé sur ce module (mocks `automationsService`, structure `describe`/`it`) — copier ce pattern plutôt que d'en inventer un nouveau. S'il n'existe aucun test pour ce module, s'inspirer du pattern `tests/unit/features/trajets/useAnnouncementForm.spec.ts` (mock du service via `vi.mock`, `beforeEach` avec `vi.clearAllMocks()`).

- [ ] **Step 2: Ajouter `hoursBeforeDeparture` au type `PresetRuleConfig`**

```typescript
// app/features/automations/types/index.ts — remplacer l'interface existante
export interface PresetRuleConfig {
  minRating?: number
  minFreeKg?: number
  minFreeHours?: number
  hoursBeforeDeparture?: number
}
```

- [ ] **Step 3: Écrire le test RED pour `updatePresetConfig`**

```typescript
// tests/unit/features/automations/useAutomations.spec.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'

const mockUpdateRule = vi.fn()
const mockListRules = vi.fn()

vi.mock('@/features/automations/services/automationsService', () => ({
  automationsService: () => ({
    listRules: mockListRules,
    updateRule: mockUpdateRule,
    createRule: vi.fn(),
    deleteRule: vi.fn(),
  }),
}))

describe('useAutomations — updatePresetConfig', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockListRules.mockResolvedValue([])
  })

  it('sends enabled=true and the new config, then refetches', async () => {
    const { useAutomations } = await import('@/features/automations/composables/useAutomations')
    const { presetRules, fetchRules, updatePresetConfig } = useAutomations()
    await fetchRules()
    mockListRules.mockResolvedValue([
      { id: 'auto_accept_trusted', ruleType: 'PRESET', enabled: true, label: 'x', description: 'x', isConfigurable: true, config: {} },
    ])
    // Simule un preset déjà chargé et activé
    presetRules.value = [
      { id: 'auto_accept_trusted', ruleType: 'PRESET', enabled: true, label: 'x', description: 'x', isConfigurable: true, config: {} },
    ] as any

    await updatePresetConfig('auto_accept_trusted', { minRating: 4.2 })

    expect(mockUpdateRule).toHaveBeenCalledWith('auto_accept_trusted', {
      enabled: true,
      config: { minRating: 4.2 },
    })
  })
})
```

- [ ] **Step 4: Run test → confirmer échec**

Run: `node_modules/.bin/vitest run tests/unit/features/automations/useAutomations.spec.ts`
Expected: FAIL — `updatePresetConfig` n'existe pas.

- [ ] **Step 5: Ajouter `updatePresetConfig` dans `useAutomations.ts`**

Le back (Task 4) lit les clés `freedKgThreshold`/`consecutiveHours` depuis `rule.getAction()`. Le front garde ses noms de variable internes `minFreeKg`/`minFreeHours` (déjà présents dans `PresetRuleConfig` avant ce chantier, pas renommés pour limiter le diff) — `updatePresetConfig` mappe explicitement vers les clés attendues par le back avant l'envoi :

```typescript
// Ajouter après togglePreset dans useAutomations.ts
async function updatePresetConfig(id: string, config: PresetRuleConfig): Promise<void> {
  const preset = presetRules.value.find((r) => r.id === id)
  if (!preset) return
  const wireConfig: Record<string, number> = {}
  if (config.minRating !== undefined) wireConfig.minRating = config.minRating
  if (config.minFreeKg !== undefined) wireConfig.freedKgThreshold = config.minFreeKg
  if (config.minFreeHours !== undefined) wireConfig.consecutiveHours = config.minFreeHours
  if (config.hoursBeforeDeparture !== undefined) wireConfig.hoursBeforeDeparture = config.hoursBeforeDeparture
  await svc.updateRule(id, { enabled: preset.enabled, config: wireConfig })
  await fetchRules()
}
```

Ajouter `updatePresetConfig` à l'objet retourné par `useAutomations()`.

- [ ] **Step 6: Run test → GREEN**

Run: `node_modules/.bin/vitest run tests/unit/features/automations/useAutomations.spec.ts`
Expected: PASS.

- [ ] **Step 7: Ajouter les champs de seuils dans `PresetRuleCard.vue`**

Ajouter un state local d'édition et les inputs correspondants, affichés uniquement quand `rule.isConfigurable` est vrai (déjà le cas pour `auto_accept_trusted`, `alert_capacity_free`, `alert_last_minute_bid`) :

```vue
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { cn } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'
import type { PresetRule, PresetRuleConfig } from '@/features/automations/types/index'

const props = defineProps<{
  rule: PresetRule
  isUpdating: boolean
}>()

const emit = defineEmits<{
  'toggle': [id: string]
  'update-config': [id: string, config: PresetRuleConfig]
}>()

const ruleDescriptionMap: Record<string, string> = {
  auto_accept_trusted:
    'Accepte automatiquement les bids dont la note expéditeur ≥ seuil configuré ET le poids ≤ capacité restante.',
  auto_reject_overweight:
    'Refuse automatiquement avec un message poli les bids dont le poids demandé dépasse ta capacité restante.',
  auto_close_full:
    "Passe l'annonce en statut FULL dès que ta capacité restante atteint 0 kg.",
  alert_capacity_free:
    'Envoie un push + email quand tu as plus de X kg libres depuis plus de Y heures consécutives.',
  notify_loyal_senders:
    "Invite tes expéditeurs historiques dès qu'une nouvelle annonce est publiée sur leurs corridors habituels.",
  alert_last_minute_bid:
    'Déclenche un push immédiat quand un bid arrive avec un départ dans moins de 48 h.',
}

const description = computed(
  () => ruleDescriptionMap[props.rule.id] ?? props.rule.description,
)

const localConfig = ref<PresetRuleConfig>({ ...props.rule.config })
watch(() => props.rule.config, (c) => { localConfig.value = { ...c } })

function saveConfig() {
  emit('update-config', props.rule.id, { ...localConfig.value })
}
</script>

<template>
  <div
    :data-test="`preset-rule-card-${rule.id}`"
    :class="cn(
      'flex items-start gap-4 rounded-el border bg-surface p-5 shadow-card transition-all duration-200',
      rule.enabled ? 'border-primary/40' : 'border-border',
    )"
  >
    <div class="flex-shrink-0 pt-0.5">
      <button
        :data-test="`preset-toggle-${rule.id}`"
        :disabled="isUpdating"
        :aria-label="rule.enabled ? `Désactiver ${rule.label}` : `Activer ${rule.label}`"
        :aria-checked="rule.enabled"
        :class="cn(
          'relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary',
          rule.enabled ? 'bg-primary' : 'bg-border',
          isUpdating && 'opacity-50 cursor-not-allowed',
        )"
        type="button"
        role="switch"
        @click="!isUpdating && emit('toggle', rule.id)"
      >
        <span
          :class="cn(
            'inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform duration-200',
            rule.enabled ? 'translate-x-6' : 'translate-x-1',
          )"
        />
      </button>
    </div>

    <div class="flex-1 min-w-0 space-y-1">
      <div class="flex items-center gap-2 flex-wrap">
        <p class="text-sm font-semibold text-text">{{ rule.label }}</p>
        <Badge v-if="rule.enabled" variant="success" size="sm">Active</Badge>
        <Badge v-else variant="neutral" size="sm">Inactive</Badge>
      </div>
      <p class="text-xs text-text-muted leading-relaxed">{{ description }}</p>

      <div v-if="rule.isConfigurable" class="mt-2 flex flex-wrap items-end gap-3">
        <div v-if="rule.id === 'auto_accept_trusted'">
          <label class="block text-2xs text-text-muted mb-0.5">Note minimum</label>
          <input
            v-model.number="localConfig.minRating"
            type="number" min="1" max="5" step="0.1"
            data-test="config-min-rating"
            class="w-20 rounded-input border border-border-strong bg-surface px-2 py-1 text-sm"
            @change="saveConfig"
          />
        </div>
        <template v-if="rule.id === 'alert_capacity_free'">
          <div>
            <label class="block text-2xs text-text-muted mb-0.5">kg libres</label>
            <input
              v-model.number="localConfig.minFreeKg"
              type="number" min="1" step="1"
              data-test="config-min-free-kg"
              class="w-20 rounded-input border border-border-strong bg-surface px-2 py-1 text-sm"
              @change="saveConfig"
            />
          </div>
          <div>
            <label class="block text-2xs text-text-muted mb-0.5">heures consécutives</label>
            <input
              v-model.number="localConfig.minFreeHours"
              type="number" min="1" step="1"
              data-test="config-min-free-hours"
              class="w-20 rounded-input border border-border-strong bg-surface px-2 py-1 text-sm"
              @change="saveConfig"
            />
          </div>
        </template>
        <div v-if="rule.id === 'alert_last_minute_bid'">
          <label class="block text-2xs text-text-muted mb-0.5">heures avant départ</label>
          <input
            v-model.number="localConfig.hoursBeforeDeparture"
            type="number" min="1" step="1"
            data-test="config-hours-before-departure"
            class="w-20 rounded-input border border-border-strong bg-surface px-2 py-1 text-sm"
            @change="saveConfig"
          />
        </div>
      </div>
    </div>
  </div>
</template>
```

Rappel : `updatePresetConfig` (Step 5) mappe déjà `minFreeKg → freedKgThreshold` et `minFreeHours → consecutiveHours` avant l'envoi — les champs ci-dessous restent liés à `localConfig.minFreeKg`/`localConfig.minFreeHours` (noms front), pas aux noms wire.

- [ ] **Step 8: Brancher `update-config` dans `AutomationsDashboard.vue`**

Lire `AutomationsDashboard.vue` pour localiser où `PresetRuleCard` est monté et où `@toggle="..."` est déjà géré ; ajouter `@update-config="updatePresetConfig"` sur le même composant, relié au `updatePresetConfig` exposé par `useAutomations()`.

- [ ] **Step 9: Run suite front complète**

Run: `node_modules/.bin/vitest run`
Expected: tous les tests passent, aucune régression.

- [ ] **Step 10: Commit**

```bash
git add app/features/automations/ tests/unit/features/automations/
git commit -m "feat(automations): UI de configuration des seuils (note min, kg/heures, heures avant départ)"
```

## Self-Review Checklist (à faire par le contrôleur avant de lancer l'exécution)

- [x] Spec coverage : les 5 règles en scope ont chacune une tâche (1→3, 4, 5) ; règle 3 explicitement exclue.
- [x] Aucun placeholder — chaque step contient du code réel basé sur la lecture du code actuel.
- [x] Cohérence des types : `AutomationRuleEntity.getAction()`/`setAction()` utilisé de façon cohérente dans toutes les tâches pour stocker la config des presets.
- [x] Divergence de nommage `minFreeKg/minFreeHours` (front) vs `freedKgThreshold/consecutiveHours` (back) tranchée : mapping explicite dans `updatePresetConfig` (Task 6, Step 5), pas de renommage de type front.
