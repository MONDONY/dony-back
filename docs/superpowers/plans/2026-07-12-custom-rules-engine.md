# Moteur d'exécution des règles personnalisées (SI→ALORS) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Faire exécuter les règles personnalisées SI→ALORS (actions `auto_accept`/`auto_reject`) créées par le voyageur pro, sur le même déclencheur `BidCreatedEvent` que les presets.

**Architecture:** Un évaluateur pur `CustomRuleConditionEvaluator` (ET strict sur les conditions JSONB, comparaisons numériques `BigDecimal`, texte normalisé trim+lowercase, fail-safe systématique) + extension de `AutomationBidListener` en deux phases refus-puis-acceptation où **tout refus qui matche (preset ou custom) bloque toute acceptation**. Les actions passent par `AutomationActionExecutor` existant (historique + plafond quotidien partagé de 20 actions/jour).

**Tech Stack:** Spring Boot 3.4 (Java 21), JPA/JSONB, Mockito unit tests (style existant du package `automation`).

**Spec:** `docs/superpowers/specs/2026-07-12-custom-rules-engine-design.md` — la spec fait foi.

**Branche:** `feature/custom-rules-engine` (basée sur `feature/automation-engine`, PR #96 non mergée — ne pas rebaser sur `main`).

## Global Constraints

- **INTERDICTION ABSOLUE de `@Transactional`** sur `AutomationBidListener.onBidCreated` et sur toute méthode orchestrant un appel à `AutomationActionExecutor.tryExecuteBidAction` (risque `UnexpectedRollbackException` sur transaction imbriquée avec `BidService.acceptBidBySystem`/`rejectBidBySystem` — bug corrigé au chantier précédent, ne pas le réintroduire).
- Fail-safe partout : valeur null, `value` non parsable, opérateur invalide pour le type, `field`/`operator` inconnu, liste `conditions` vide → la condition/règle ne matche PAS. Jamais d'action sur un doute.
- Comparaison numérique via `BigDecimal.compareTo` (jamais `equals`). Texte : `trim().toLowerCase(Locale.ROOT)` des deux côtés, opérateur `eq` uniquement.
- Une seule action bid (accept XOR reject) par bid. Tout refus qui **matche** bloque la phase acceptation, même si son exécution échoue (plafond/erreur).
- `actionTaken` dans l'historique : exactement `"CUSTOM_AUTO_REJECT"` et `"CUSTOM_AUTO_ACCEPT"`.
- Motif de refus custom : `action.message` si non vide, sinon `"Refusé automatiquement par une règle du voyageur : {rule.name}."`
- Les règles custom d'action `send_alert`/`trigger_search`/`invite_sender`/`close_announcement` sont ignorées (jamais évaluées, aucun historique).
- Ordre entre customs de même type : `createdAt` croissant (déjà l'ordre de `findByTravelerIdOrderByCreatedAtAsc`), première qui matche gagne.
- TDD strict (RED avant GREEN), commits fréquents, pas de `Co-Authored-By: Claude`, messages de commit en français type `feat(automation): …`.
- Aucun changement de schéma, aucun changement front (dony-pro), aucun changement Flutter.

---

### Task 1: `BidEvaluationContext` + `CustomRuleConditionEvaluator` (évaluateur pur)

**Files:**
- Create: `src/main/java/com/dony/api/automation/BidEvaluationContext.java`
- Create: `src/main/java/com/dony/api/automation/CustomRuleConditionEvaluator.java`
- Test: `src/test/java/com/dony/api/automation/CustomRuleConditionEvaluatorTest.java`

**Interfaces:**
- Consomme : `AutomationRuleEntity` existante (`getConditions(): List<Map<String,Object>>`, `getId(): UUID` hérité de `BaseEntity`).
- Produit (utilisé par Task 2) :
  - `record BidEvaluationContext(BigDecimal weightKg, String corridor, String contentCategory, BigDecimal senderRating, BigDecimal capacityFreeKg, Long hoursBeforeDeparture)` — package-private, tout champ nullable.
  - `static boolean CustomRuleConditionEvaluator.matches(AutomationRuleEntity rule, BidEvaluationContext ctx)` — package-private.

- [ ] **Step 1: Écrire les tests qui échouent**

Créer `src/test/java/com/dony/api/automation/CustomRuleConditionEvaluatorTest.java` :

```java
package com.dony.api.automation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomRuleConditionEvaluatorTest {

    private static final BidEvaluationContext FULL_CTX = new BidEvaluationContext(
            new BigDecimal("8"),          // weightKg
            "Paris → Dakar",              // corridor
            "Poissons",                   // contentCategory
            new BigDecimal("4.5"),        // senderRating
            new BigDecimal("12"),         // capacityFreeKg
            36L);                         // hoursBeforeDeparture

    private AutomationRuleEntity rule(List<Map<String, Object>> conditions) {
        AutomationRuleEntity r = new AutomationRuleEntity();
        r.setRuleType("CUSTOM");
        r.setName("Ma règle");
        r.setConditions(conditions);
        return r;
    }

    private Map<String, Object> cond(String field, String operator, String value) {
        return Map.of("field", field, "operator", operator, "value", value);
    }

    // --- Champs numériques : gte / lte / eq ---

    @Test
    void weightKg_gte_matchesWhenAboveOrEqual() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "gte", "8"))), FULL_CTX));
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "gte", "5"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "gte", "9"))), FULL_CTX));
    }

    @Test
    void weightKg_lte_matchesWhenBelowOrEqual() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "lte", "8"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "lte", "7"))), FULL_CTX));
    }

    @Test
    void senderRating_gte_worksOnRating() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("sender_rating", "gte", "4.0"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("sender_rating", "gte", "4.6"))), FULL_CTX));
    }

    @Test
    void capacityFreeKg_lte_worksOnCapacity() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("capacity_free_kg", "lte", "12"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("capacity_free_kg", "lte", "11"))), FULL_CTX));
    }

    @Test
    void hoursBeforeDeparture_lte_worksOnDerivedHours() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("hours_before_departure", "lte", "48"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("hours_before_departure", "lte", "24"))), FULL_CTX));
    }

    @Test
    void numericEq_ignoresBigDecimalScale() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "eq", "8.00"))), FULL_CTX));
    }

    // --- Champs texte : eq normalisé ---

    @Test
    void contentType_eq_isCaseAndWhitespaceInsensitive() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("content_type", "eq", " poissons "))), FULL_CTX));
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("content_type", "eq", "POISSONS"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("content_type", "eq", "poisson frais"))), FULL_CTX));
    }

    @Test
    void corridor_eq_matchesNormalized() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("corridor", "eq", "paris → dakar"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("corridor", "eq", "Paris → Abidjan"))), FULL_CTX));
    }

    // --- ET strict ---

    @Test
    void allConditionsMustMatch_andStrict() {
        AutomationRuleEntity r = rule(List.of(
                cond("content_type", "eq", "Poissons"),
                cond("weight_kg", "gte", "5")));
        assertTrue(CustomRuleConditionEvaluator.matches(r, FULL_CTX));

        AutomationRuleEntity r2 = rule(List.of(
                cond("content_type", "eq", "Poissons"),
                cond("weight_kg", "gte", "20")));
        assertFalse(CustomRuleConditionEvaluator.matches(r2, FULL_CTX));
    }

    // --- Fail-safe ---

    @Test
    void emptyConditions_neverMatches() {
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of()), FULL_CTX));
    }

    @Test
    void nullContextValue_conditionNotSatisfied() {
        BidEvaluationContext ctx = new BidEvaluationContext(null, null, null, null, null, null);
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "gte", "1"))), ctx));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("content_type", "eq", "Poissons"))), ctx));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("sender_rating", "lte", "5"))), ctx));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("hours_before_departure", "gte", "0"))), ctx));
    }

    @Test
    void unparsableNumericValue_conditionNotSatisfied() {
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "gte", "lourd"))), FULL_CTX));
    }

    @Test
    void gteLteOnTextField_conditionNotSatisfied() {
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("content_type", "gte", "Poissons"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("corridor", "lte", "Paris → Dakar"))), FULL_CTX));
    }

    @Test
    void unknownFieldOrOperator_conditionNotSatisfied() {
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("declared_value", "gte", "100"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "neq", "8"))), FULL_CTX));
    }

    @Test
    void incompleteCondition_missingKeys_notSatisfied() {
        AutomationRuleEntity r = rule(List.of(Map.of("field", "weight_kg")));
        assertFalse(CustomRuleConditionEvaluator.matches(r, FULL_CTX));
    }
}
```

- [ ] **Step 2: Vérifier que les tests échouent (compilation)**

Run: `cd /Users/aboubakardiakite/Desktop/dony/dony-back && ./mvnw test -Dtest=CustomRuleConditionEvaluatorTest`
Expected: FAIL — erreur de compilation, `BidEvaluationContext` et `CustomRuleConditionEvaluator` n'existent pas.

- [ ] **Step 3: Implémentation minimale**

Créer `src/main/java/com/dony/api/automation/BidEvaluationContext.java` :

```java
package com.dony.api.automation;

import java.math.BigDecimal;

/**
 * Valeurs résolues une fois par bid par AutomationBidListener, consommées par
 * CustomRuleConditionEvaluator. Tout champ peut être null (donnée absente) —
 * une condition portant sur un champ null n'est jamais satisfaite (fail-safe).
 */
record BidEvaluationContext(
        BigDecimal weightKg,
        String corridor,
        String contentCategory,
        BigDecimal senderRating,
        BigDecimal capacityFreeKg,
        Long hoursBeforeDeparture) {
}
```

Créer `src/main/java/com/dony/api/automation/CustomRuleConditionEvaluator.java` :

```java
package com.dony.api.automation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Évalue les conditions d'une règle personnalisée (ruleType=CUSTOM) contre le
 * contexte d'un bid. Pur, sans accès base : toutes les valeurs nécessaires
 * sont résolues en amont dans {@link BidEvaluationContext}.
 *
 * <p>Sémantique fail-safe : dans le doute (valeur absente, {@code value} non
 * parsable, opérateur invalide pour le type de champ, field/operator inconnu,
 * liste de conditions vide), la règle ne matche PAS — on n'agit jamais sur un
 * doute. Les conditions sont combinées en ET strict.
 */
final class CustomRuleConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CustomRuleConditionEvaluator.class);

    private CustomRuleConditionEvaluator() {
    }

    /** true uniquement si TOUTES les conditions de la règle sont satisfaites (ET strict). */
    static boolean matches(AutomationRuleEntity rule, BidEvaluationContext ctx) {
        List<Map<String, Object>> conditions = rule.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            return false;
        }
        for (Map<String, Object> condition : conditions) {
            if (!conditionSatisfied(rule, condition, ctx)) {
                return false;
            }
        }
        return true;
    }

    private static boolean conditionSatisfied(AutomationRuleEntity rule, Map<String, Object> condition,
                                              BidEvaluationContext ctx) {
        String field = asString(condition.get("field"));
        String operator = asString(condition.get("operator"));
        String value = asString(condition.get("value"));
        if (field == null || operator == null || value == null) {
            log.warn("Automation custom rule {}: condition incomplète {}", rule.getId(), condition);
            return false;
        }
        return switch (field) {
            case "sender_rating" -> numericCompare(rule, ctx.senderRating(), operator, value);
            case "weight_kg" -> numericCompare(rule, ctx.weightKg(), operator, value);
            case "capacity_free_kg" -> numericCompare(rule, ctx.capacityFreeKg(), operator, value);
            case "hours_before_departure" -> numericCompare(rule,
                    ctx.hoursBeforeDeparture() == null ? null : BigDecimal.valueOf(ctx.hoursBeforeDeparture()),
                    operator, value);
            case "corridor" -> textEquals(rule, ctx.corridor(), operator, value);
            case "content_type" -> textEquals(rule, ctx.contentCategory(), operator, value);
            default -> {
                log.warn("Automation custom rule {}: field inconnu '{}'", rule.getId(), field);
                yield false;
            }
        };
    }

    private static boolean numericCompare(AutomationRuleEntity rule, BigDecimal actual,
                                          String operator, String rawValue) {
        if (actual == null) {
            return false;
        }
        BigDecimal expected;
        try {
            expected = new BigDecimal(rawValue.trim());
        } catch (NumberFormatException e) {
            log.warn("Automation custom rule {}: valeur numérique non parsable '{}'", rule.getId(), rawValue);
            return false;
        }
        int cmp = actual.compareTo(expected);
        return switch (operator) {
            case "gte" -> cmp >= 0;
            case "lte" -> cmp <= 0;
            case "eq" -> cmp == 0;
            default -> {
                log.warn("Automation custom rule {}: operator inconnu '{}'", rule.getId(), operator);
                yield false;
            }
        };
    }

    private static boolean textEquals(AutomationRuleEntity rule, String actual,
                                      String operator, String rawValue) {
        if (!"eq".equals(operator)) {
            log.warn("Automation custom rule {}: operator '{}' invalide sur un champ texte", rule.getId(), operator);
            return false;
        }
        if (actual == null) {
            return false;
        }
        return normalize(actual).equals(normalize(rawValue));
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
```

- [ ] **Step 4: Vérifier que les tests passent**

Run: `./mvnw test -Dtest=CustomRuleConditionEvaluatorTest`
Expected: PASS, 15 tests verts.

- [ ] **Step 5: Suite complète du package automation**

Run: `./mvnw test -Dtest='com.dony.api.automation.*'`
Expected: PASS, aucune régression.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/dony/api/automation/BidEvaluationContext.java \
        src/main/java/com/dony/api/automation/CustomRuleConditionEvaluator.java \
        src/test/java/com/dony/api/automation/CustomRuleConditionEvaluatorTest.java
git commit -m "feat(automation): évaluateur pur des conditions de règles personnalisées"
```

---

### Task 2: Intégration dans `AutomationBidListener` (phases refus/acceptation, customs + presets)

**Files:**
- Modify: `src/main/java/com/dony/api/automation/AutomationBidListener.java`
- Test: `src/test/java/com/dony/api/automation/AutomationBidListenerTest.java` (étendre, ne pas réécrire)

**Interfaces:**
- Consomme (Task 1) : `CustomRuleConditionEvaluator.matches(AutomationRuleEntity, BidEvaluationContext)` (statique, package-private) et le record `BidEvaluationContext(BigDecimal weightKg, String corridor, String contentCategory, BigDecimal senderRating, BigDecimal capacityFreeKg, Long hoursBeforeDeparture)`.
- Consomme (existant) : `BidRepository` (`com.dony.api.matching`, `findById(UUID): Optional<BidEntity>`), `BidEntity.getContentCategory(): String`, `UserEntity.getAverageRating(): BigDecimal`, `AnnouncementEntity.getAvailableKg(): BigDecimal` / `getDepartureAt(): OffsetDateTime`, `AutomationActionExecutor.tryExecuteBidAction(rule, travelerId, bidId, actionTaken, Supplier<Void>): boolean`, `BidService.acceptBidBySystem(UUID, UUID)` / `rejectBidBySystem(UUID, UUID, String)`.
- Produit : nouveau paramètre de constructeur `BidRepository bidRepository` (dernier paramètre — les tests existants instancient le listener à la main et devront être mis à jour).

**⚠️ Piège connu :** ne JAMAIS mettre `@Transactional` sur cette classe ni sur `onBidCreated` (voir Global Constraints et la Javadoc existante de la classe — elle explique le pourquoi).

**Changement de sémantique assumé (spec, point 5)** : le booléen actuel `rejected` (= refus *exécuté*) devient `rejectMatched` (= une règle de refus a *matché*, même si l'exécution a été bloquée par le plafond ou a échoué). Un colis visé par un refus ne doit jamais être auto-accepté. Le comportement preset est aligné sur cette sémantique dans le même mouvement.

- [ ] **Step 1: Écrire les tests qui échouent**

Dans `src/test/java/com/dony/api/automation/AutomationBidListenerTest.java` :

1. Ajouter les imports manquants :

```java
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
```

2. Ajouter le mock et mettre à jour le constructeur dans `setUp()` :

```java
@Mock private BidRepository bidRepository;
```

```java
listener = new AutomationBidListener(ruleRepository, executor, bidService,
        userRepository, announcementRepository, notificationDispatcher, bidRepository);
```

3. Ajouter les helpers en bas de classe (à côté de `presetRule`) :

```java
private AutomationRuleEntity customRule(String name, String actionType, String message,
                                        List<Map<String, Object>> conditions) {
    AutomationRuleEntity r = new AutomationRuleEntity();
    r.setTravelerId(travelerId);
    r.setRuleType("CUSTOM");
    r.setName(name);
    r.setEnabled(true);
    r.setConditions(conditions);
    r.setAction(message == null
            ? Map.of("type", actionType)
            : Map.of("type", actionType, "message", message));
    return r;
}

private void stubBid(String contentCategory) {
    BidEntity bid = new BidEntity();
    bid.setContentCategory(contentCategory);
    when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
}

private AnnouncementEntity stubAnnouncement(String availableKg) {
    AnnouncementEntity announcement = new AnnouncementEntity();
    announcement.setAvailableKg(new BigDecimal(availableKg));
    when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
    return announcement;
}

private void stubSender(String rating) {
    UserEntity sender = new UserEntity();
    if (rating != null) sender.setAverageRating(new BigDecimal(rating));
    when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
}

private BidCreatedEvent event(String weightKg) {
    return new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
            "Awa", weightKg == null ? null : new BigDecimal(weightKg), "Paris → Dakar");
}
```

4. Ajouter les nouveaux tests :

```java
@Test
void customReject_matching_rejectsBidWithCustomMessage() {
    stubAnnouncement("20");
    stubSender("3.0");
    stubBid("Poissons");
    when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
            customRule("Refuser aliments", "auto_reject", "Pas de denrées périssables.",
                    List.of(Map.of("field", "content_type", "operator", "eq", "value", "Poissons")))));

    listener.onBidCreated(event("8"));

    verify(executor).tryExecuteBidAction(any(), eq(travelerId), eq(bidId),
            eq("CUSTOM_AUTO_REJECT"), any());
    verify(bidService).rejectBidBySystem(bidId, travelerId, "Pas de denrées périssables.");
    verify(bidService, never()).acceptBidBySystem(any(), any());
}

@Test
void customReject_withoutMessage_usesFallbackReasonWithRuleName() {
    stubAnnouncement("20");
    stubSender("3.0");
    stubBid("Poissons");
    when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
            customRule("Refuser aliments", "auto_reject", null,
                    List.of(Map.of("field", "content_type", "operator", "eq", "value", "poissons")))));

    listener.onBidCreated(event("8"));

    verify(bidService).rejectBidBySystem(bidId, travelerId,
            "Refusé automatiquement par une règle du voyageur : Refuser aliments.");
}

@Test
void customReject_beatsPresetAccept_evenIfSenderTrusted() {
    stubAnnouncement("20");
    stubSender("5.0");
    stubBid("Poissons");
    when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
            presetRule("auto_accept_trusted", true, Map.of("minRating", 4.0)),
            customRule("Refuser aliments", "auto_reject", null,
                    List.of(Map.of("field", "content_type", "operator", "eq", "value", "Poissons")))));

    listener.onBidCreated(event("8"));

    verify(bidService).rejectBidBySystem(eq(bidId), eq(travelerId), any());
    verify(bidService, never()).acceptBidBySystem(any(), any());
}

@Test
void presetReject_blocksCustomAccept() {
    stubAnnouncement("5");
    stubSender("5.0");
    stubBid("Vêtements");
    when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
            presetRule("auto_reject_overweight", true, Map.of()),
            customRule("Accepter légers", "auto_accept", null,
                    List.of(Map.of("field", "weight_kg", "operator", "gte", "value", "1")))));

    listener.onBidCreated(event("10"));

    verify(bidService).rejectBidBySystem(eq(bidId), eq(travelerId), any());
    verify(bidService, never()).acceptBidBySystem(any(), any());
}

@Test
void rejectMatched_butExecutionBlocked_stillBlocksAccept() {
    // Le plafond quotidien bloque l'exécution du refus (tryExecuteBidAction -> false)
    // mais une règle de refus a MATCHÉ : l'acceptation doit rester bloquée.
    when(executor.tryExecuteBidAction(any(), any(), any(), any(), any())).thenReturn(false);
    stubAnnouncement("20");
    stubSender("5.0");
    stubBid("Poissons");
    when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
            customRule("Refuser aliments", "auto_reject", null,
                    List.of(Map.of("field", "content_type", "operator", "eq", "value", "Poissons"))),
            customRule("Accepter tout", "auto_accept", null,
                    List.of(Map.of("field", "weight_kg", "operator", "gte", "value", "1")))));

    listener.onBidCreated(event("8"));

    verify(executor).tryExecuteBidAction(any(), eq(travelerId), eq(bidId), eq("CUSTOM_AUTO_REJECT"), any());
    verify(executor, never()).tryExecuteBidAction(any(), any(), any(), eq("CUSTOM_AUTO_ACCEPT"), any());
}

@Test
void twoCustomRejectsMatch_onlyFirstExecuted() {
    stubAnnouncement("20");
    stubSender("3.0");
    stubBid("Poissons");
    when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
            customRule("Règle A", "auto_reject", "Motif A",
                    List.of(Map.of("field", "content_type", "operator", "eq", "value", "Poissons"))),
            customRule("Règle B", "auto_reject", "Motif B",
                    List.of(Map.of("field", "weight_kg", "operator", "gte", "value", "1")))));

    listener.onBidCreated(event("8"));

    verify(executor, times(1)).tryExecuteBidAction(any(), any(), any(), eq("CUSTOM_AUTO_REJECT"), any());
    verify(bidService).rejectBidBySystem(bidId, travelerId, "Motif A");
}

@Test
void customAccept_matching_acceptsBid() {
    stubAnnouncement("20");
    stubSender("3.0");
    stubBid("Vêtements");
    when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
            customRule("Accepter Dakar", "auto_accept", null,
                    List.of(Map.of("field", "corridor", "operator", "eq", "value", "paris → dakar")))));

    listener.onBidCreated(event("8"));

    verify(executor).tryExecuteBidAction(any(), eq(travelerId), eq(bidId), eq("CUSTOM_AUTO_ACCEPT"), any());
    verify(bidService).acceptBidBySystem(bidId, travelerId);
}

@Test
void presetAcceptMatched_customAcceptSkipped() {
    stubAnnouncement("20");
    stubSender("4.8");
    stubBid("Vêtements");
    when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
            presetRule("auto_accept_trusted", true, Map.of("minRating", 4.0)),
            customRule("Accepter tout", "auto_accept", null,
                    List.of(Map.of("field", "weight_kg", "operator", "gte", "value", "1")))));

    listener.onBidCreated(event("8"));

    verify(executor, times(1)).tryExecuteBidAction(any(), any(), any(), any(), any());
    verify(executor).tryExecuteBidAction(any(), eq(travelerId), eq(bidId), eq("AUTO_ACCEPT_TRUSTED"), any());
}

@Test
void customRule_unsupportedActionType_ignored() {
    stubAnnouncement("20");
    stubSender("3.0");
    stubBid("Poissons");
    when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
            customRule("Alerte aliments", "send_alert", null,
                    List.of(Map.of("field", "content_type", "operator", "eq", "value", "Poissons")))));

    listener.onBidCreated(event("8"));

    verify(executor, never()).tryExecuteBidAction(any(), any(), any(), any(), any());
    verifyNoInteractions(bidService);
}

@Test
void customRule_disabled_ignored() {
    stubAnnouncement("20");
    stubSender("3.0");
    stubBid("Poissons");
    AutomationRuleEntity disabled = customRule("Refuser aliments", "auto_reject", null,
            List.of(Map.of("field", "content_type", "operator", "eq", "value", "Poissons")));
    disabled.setEnabled(false);
    when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(disabled));

    listener.onBidCreated(event("8"));

    verifyNoInteractions(bidService);
}

@Test
void bidNotFound_customRulesSkipped_noAction() {
    stubAnnouncement("20");
    stubSender("3.0");
    when(bidRepository.findById(bidId)).thenReturn(Optional.empty());
    when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
            customRule("Refuser aliments", "auto_reject", null,
                    List.of(Map.of("field", "content_type", "operator", "eq", "value", "Poissons")))));

    listener.onBidCreated(event("8"));

    verifyNoInteractions(bidService);
}
```

Note : si `BidEntity` ne se construit pas par `new BidEntity()` + setter (vérifier le constructeur réel), utiliser `mock(BidEntity.class)` + `when(bid.getContentCategory()).thenReturn(...)`. Le fichier réel fait foi.

- [ ] **Step 2: Vérifier que les tests échouent**

Run: `./mvnw test -Dtest=AutomationBidListenerTest`
Expected: FAIL — erreur de compilation (constructeur à 7 paramètres inexistant).

- [ ] **Step 3: Implémentation**

Modifier `src/main/java/com/dony/api/automation/AutomationBidListener.java` :

1. Imports supplémentaires :

```java
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import java.time.Duration;
```

2. Champ + constructeur (ajouter `bidRepository` en dernier paramètre) :

```java
private final BidRepository bidRepository;

public AutomationBidListener(AutomationRuleRepository ruleRepository,
                             AutomationActionExecutor executor,
                             BidService bidService,
                             UserRepository userRepository,
                             AnnouncementRepository announcementRepository,
                             NotificationDispatcher notificationDispatcher,
                             BidRepository bidRepository) {
    this.ruleRepository = ruleRepository;
    this.executor = executor;
    this.bidService = bidService;
    this.userRepository = userRepository;
    this.announcementRepository = announcementRepository;
    this.notificationDispatcher = notificationDispatcher;
    this.bidRepository = bidRepository;
}
```

3. Remplacer intégralement le corps de `onBidCreated` (l'annotation `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` reste, toujours PAS de `@Transactional`) :

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onBidCreated(BidCreatedEvent event) {
    List<AutomationRuleEntity> rules =
            ruleRepository.findByTravelerIdOrderByCreatedAtAsc(event.getTravelerId());

    Optional<AutomationRuleEntity> rejectRule = findEnabledPreset(rules, "auto_reject_overweight");
    Optional<AutomationRuleEntity> acceptRule = findEnabledPreset(rules, "auto_accept_trusted");
    Optional<AutomationRuleEntity> lastMinuteRule = findEnabledPreset(rules, "alert_last_minute_bid");
    List<AutomationRuleEntity> customRejectRules = findEnabledCustom(rules, "auto_reject");
    List<AutomationRuleEntity> customAcceptRules = findEnabledCustom(rules, "auto_accept");

    AnnouncementEntity announcement = announcementRepository.findById(event.getAnnouncementId())
            .orElse(null);
    if (announcement == null) {
        log.warn("Automation: announcement {} not found for bid {}", event.getAnnouncementId(), event.getBidId());
        return;
    }

    UserEntity sender = null;
    if (acceptRule.isPresent() || !customRejectRules.isEmpty() || !customAcceptRules.isEmpty()) {
        sender = userRepository.findById(event.getSenderId()).orElse(null);
    }

    BidEvaluationContext ctx = null;
    if (!customRejectRules.isEmpty() || !customAcceptRules.isEmpty()) {
        BidEntity bid = bidRepository.findById(event.getBidId()).orElse(null);
        if (bid == null) {
            log.warn("Automation: bid {} not found, custom rules skipped", event.getBidId());
        } else {
            ctx = new BidEvaluationContext(
                    event.getWeightKg(),
                    event.getCorridor(),
                    bid.getContentCategory(),
                    sender != null ? sender.getAverageRating() : null,
                    announcement.getAvailableKg(),
                    announcement.getDepartureAt() == null ? null
                            : Duration.between(OffsetDateTime.now(), announcement.getDepartureAt()).toHours());
        }
    }

    // Phase refus. Toute règle de refus qui MATCHE bloque la phase acceptation,
    // même si son exécution est bloquée (plafond) ou échoue : un colis visé par
    // un refus ne doit jamais être auto-accepté par une autre règle.
    boolean rejectMatched = false;
    if (rejectRule.isPresent() && event.getWeightKg() != null
            && event.getWeightKg().compareTo(announcement.getAvailableKg()) > 0) {
        rejectMatched = true;
        executor.tryExecuteBidAction(rejectRule.get(), event.getTravelerId(), event.getBidId(),
                "AUTO_REJECT_OVERWEIGHT", () -> {
                    bidService.rejectBidBySystem(event.getBidId(), event.getTravelerId(),
                            "Le poids de ce colis dépasse la capacité restante sur ce trajet.");
                    return null;
                });
    }
    if (!rejectMatched && ctx != null) {
        for (AutomationRuleEntity rule : customRejectRules) {
            if (CustomRuleConditionEvaluator.matches(rule, ctx)) {
                rejectMatched = true;
                String reason = customRejectReason(rule);
                executor.tryExecuteBidAction(rule, event.getTravelerId(), event.getBidId(),
                        "CUSTOM_AUTO_REJECT", () -> {
                            bidService.rejectBidBySystem(event.getBidId(), event.getTravelerId(), reason);
                            return null;
                        });
                break;
            }
        }
    }

    // Phase acceptation — une seule action bid par bid (accept XOR reject).
    boolean acceptMatched = false;
    if (!rejectMatched && acceptRule.isPresent()) {
        AutomationRuleEntity rule = acceptRule.get();
        BigDecimal minRating = configNumber(rule, "minRating", new BigDecimal("4.0"));
        boolean weightOk = event.getWeightKg() == null
                || event.getWeightKg().compareTo(announcement.getAvailableKg()) <= 0;
        boolean ratingOk = sender != null && sender.getAverageRating() != null
                && sender.getAverageRating().compareTo(minRating) >= 0;
        if (weightOk && ratingOk) {
            acceptMatched = true;
            executor.tryExecuteBidAction(rule, event.getTravelerId(), event.getBidId(),
                    "AUTO_ACCEPT_TRUSTED", () -> {
                        bidService.acceptBidBySystem(event.getBidId(), event.getTravelerId());
                        return null;
                    });
        }
    }
    if (!rejectMatched && !acceptMatched && ctx != null) {
        for (AutomationRuleEntity rule : customAcceptRules) {
            if (CustomRuleConditionEvaluator.matches(rule, ctx)) {
                executor.tryExecuteBidAction(rule, event.getTravelerId(), event.getBidId(),
                        "CUSTOM_AUTO_ACCEPT", () -> {
                            bidService.acceptBidBySystem(event.getBidId(), event.getTravelerId());
                            return null;
                        });
                break;
            }
        }
    }

    if (lastMinuteRule.isPresent() && announcement.getDepartureAt() != null) {
        AutomationRuleEntity rule = lastMinuteRule.get();
        int hoursBeforeDeparture = configInt(rule, "hoursBeforeDeparture", 48);
        long hoursUntilDeparture = Duration.between(
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
```

4. Ajouter les deux helpers privés (à côté de `findEnabledPreset`) :

```java
private List<AutomationRuleEntity> findEnabledCustom(List<AutomationRuleEntity> rules, String actionType) {
    return rules.stream()
            .filter(r -> "CUSTOM".equals(r.getRuleType()) && r.isEnabled())
            .filter(r -> r.getAction() != null && actionType.equals(r.getAction().get("type")))
            .toList();
}

private String customRejectReason(AutomationRuleEntity rule) {
    Object message = rule.getAction() != null ? rule.getAction().get("message") : null;
    if (message != null && !message.toString().isBlank()) {
        return message.toString();
    }
    return "Refusé automatiquement par une règle du voyageur : " + rule.getName() + ".";
}
```

Notes d'implémentation :
- Le préchargement conditionnel de `sender` remplace le chargement qui se faisait dans le bloc accept — attention aux tests existants : si un test existant échoue avec un NPE sur `userRepository.findById(...).orElse(null)` (mock non stubé retournant `null` au lieu d'`Optional`), ajouter dans ce test `when(userRepository.findById(senderId)).thenReturn(Optional.empty())`.
- `Duration.between` était en FQN inline (`java.time.Duration`) — l'import ajouté permet la forme courte partout ; ne pas laisser les deux formes.
- L'ordre `createdAt` croissant est garanti par la requête `findByTravelerIdOrderByCreatedAtAsc` ; `findEnabledCustom` préserve cet ordre (`stream().filter().toList()`).

- [ ] **Step 4: Vérifier que les nouveaux tests passent**

Run: `./mvnw test -Dtest=AutomationBidListenerTest`
Expected: PASS — tous les tests existants ET les 11 nouveaux.

- [ ] **Step 5: Suite complète**

Run: `./mvnw test`
Expected: PASS, aucune régression sur le reste du projet.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/dony/api/automation/AutomationBidListener.java \
        src/test/java/com/dony/api/automation/AutomationBidListenerTest.java
git commit -m "feat(automation): exécution des règles personnalisées auto_accept/auto_reject sur BidCreatedEvent"
```
