# Retrait valeur déclarée + politique de remboursement — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retirer entièrement le champ « valeur déclarée » des formulaires colis et le remplacer par un texte informatif expliquant la politique de remboursement yadony (plafond configurable, défaut 50 €, sous conditions, jamais automatique).

**Architecture:** Deux repos git séparés (`yadony-back` Spring Boot, `yadony_app` Flutter), branche `feature/reimbursement-policy-info` déjà créée dans les deux. Le plafond de remboursement suit le pattern existant `yadony.commission.rate` : valeur backend surchargeable par env var, exposée via `GET /config/reimbursement-cap`, chargée une fois au démarrage Flutter et cachée globalement. Le champ `declaredValueEur` est supprimé de bout en bout (colonne DB, entités, DTOs requête/réponse, events, chaîne Flutter event→bloc→datasource→model, surfaces d'affichage).

**Tech Stack:** Spring Boot 3.4 / Java 21 / Flyway / JUnit+MockMvc ; Flutter / flutter_bloc / Dio / json_serializable / bloc_test.

## Global Constraints

- Backend : ne jamais modifier une migration existante — créer `V184` (prochaine libre après `V183`). Copié de la convention CLAUDE.md.
- Backend : soft-delete only, erreurs RFC 7807 `ProblemDetail`, `@ConfigurationPropertiesScan` déjà actif sur `YadonyBackApplication` (pas de `@EnableConfigurationProperties` à ajouter).
- Backend : couverture JaCoCo ≥ 90 %, `./mvnw test` à 0 rouge avant tout commit.
- Flutter : BLoC (jamais setState), GoRouter (jamais Navigator.push), Dio. Couverture ≥ 90 %, `flutter test` à 0 rouge.
- Flutter : palette CLAUDE.md périmée (vert) — utiliser les tokens theme-aware `Theme.of(context).colorScheme.*` (`cs.*`), jamais les `k*` legacy.
- Flutter : jamais « — » (em-dash) dans un texte affiché ; virgule à la place.
- Ne jamais lancer deux commandes Flutter en parallèle (faux échecs `.dart_tool`/assets partagés).
- Commits au nom du développeur uniquement, jamais de `Co-Authored-By: Claude`.
- Montant du plafond jamais codé en dur dans un texte Dart/Java : toujours interpolé depuis la valeur config.

---

## Phase A — Backend : variable de configuration + endpoint

### Task A1 : Config `yadony.reimbursement.max-amount-eur` + nested record

**Files:**
- Modify: `yadony-back/src/main/resources/application.yml:128`
- Modify: `yadony-back/src/main/java/com/yadony/api/config/YadonyConfigProperties.java`
- Test: `yadony-back/src/test/java/com/yadony/api/config/YadonyConfigPropertiesReimbursementTest.java` (create)

**Interfaces:**
- Produces: `YadonyConfigProperties.reimbursement()` → `Reimbursement`, avec `Reimbursement.maxAmountEur()` → `BigDecimal`. Défaut 50 si absent.

- [ ] **Step 1 : Écrire le test de binding qui échoue**

Create `yadony-back/src/test/java/com/yadony/api/config/YadonyConfigPropertiesReimbursementTest.java` :

```java
package com.yadony.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class YadonyConfigPropertiesReimbursementTest {

    private YadonyConfigProperties bind(MockEnvironment env) {
        var sources = ConfigurationPropertySources.from(env.getPropertySources());
        return new Binder(sources)
                .bind("yadony", YadonyConfigProperties.class)
                .orElse(new YadonyConfigProperties(null, null, null, null));
    }

    @Test
    void bindsConfiguredReimbursementCap() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("yadony.reimbursement.max-amount-eur", "75");
        assertThat(bind(env).reimbursement().maxAmountEur())
                .isEqualByComparingTo(new BigDecimal("75"));
    }

    @Test
    void defaultsToFiftyWhenAbsent() {
        YadonyConfigProperties props = bind(new MockEnvironment());
        assertThat(props.reimbursement().maxAmountEur())
                .isEqualByComparingTo(new BigDecimal("50"));
    }
}
```

- [ ] **Step 2 : Lancer le test, vérifier l'échec**

Run: `cd yadony-back && ./mvnw test -Dtest=YadonyConfigPropertiesReimbursementTest`
Expected: FAIL — le constructeur `YadonyConfigProperties(...)` ne prend que 3 args et `reimbursement()` n'existe pas (erreur de compilation).

- [ ] **Step 3 : Ajouter le nested record `Reimbursement` à `YadonyConfigProperties`**

Dans `YadonyConfigProperties.java`, ajouter le composant `reimbursement` au record principal, son défaut dans le constructeur compact, et le nested record. Résultat complet du fichier :

```java
package com.yadony.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Yadony application configuration properties (prefix "yadony").
 * Note: yadony.stripe.* and yadony.commission-rate (legacy flat key) are
 * intentionally consumed via @Value in PaymentService for now.
 */
@ConfigurationProperties(prefix = "yadony")
public record YadonyConfigProperties(
    Commission commission,
    Limits limits,
    Urgency urgency,
    Reimbursement reimbursement
) {
    public YadonyConfigProperties {
        if (urgency == null) {
            urgency = new Urgency(null);
        }
        if (reimbursement == null) {
            reimbursement = new Reimbursement(null);
        }
    }

    public record Commission(BigDecimal rate) {}

    public record Urgency(Integer thresholdDays) {
        public Urgency {
            if (thresholdDays == null) {
                thresholdDays = 3;
            }
        }
    }

    /** Plafond de remboursement yadony en cas de perte de colis (défaut 50 €). */
    public record Reimbursement(BigDecimal maxAmountEur) {
        public Reimbursement {
            if (maxAmountEur == null) {
                maxAmountEur = new BigDecimal("50");
            }
        }
    }

    public record Limits(NonPro nonPro, Drafts drafts) {
        public record NonPro(int monthlyAnnouncements) {}
        public record Drafts(Integer max, Integer maxPro) {}

        public int monthlyAnnouncements() {
            return nonPro != null ? nonPro.monthlyAnnouncements() : 2;
        }

        public int maxDrafts() {
            return drafts != null && drafts.max() != null ? drafts.max() : 1;
        }

        public int maxDraftsPro() {
            return drafts != null && drafts.maxPro() != null ? drafts.maxPro() : 10;
        }
    }
}
```

- [ ] **Step 4 : Remplacer la clé morte dans `application.yml`**

Dans `application.yml`, remplacer la ligne 128 `  max-declared-value-eur: ${YADONY_MAX_DECLARED_VALUE_EUR:500}` par un bloc `reimbursement`. La section `yadony:` doit contenir (en gardant les autres clés intactes) :

```yaml
  reimbursement:
    max-amount-eur: ${YADONY_REIMBURSEMENT_MAX_AMOUNT_EUR:50}
```

Placer ce bloc au même niveau d'indentation que `urgency:` et `commission:`. Supprimer complètement l'ancienne ligne `max-declared-value-eur` (clé jamais bindée, morte).

- [ ] **Step 5 : Lancer le test, vérifier le succès**

Run: `cd yadony-back && ./mvnw test -Dtest=YadonyConfigPropertiesReimbursementTest`
Expected: PASS (2 tests verts).

- [ ] **Step 6 : Commit**

```bash
cd yadony-back
git add src/main/resources/application.yml \
        src/main/java/com/yadony/api/config/YadonyConfigProperties.java \
        src/test/java/com/yadony/api/config/YadonyConfigPropertiesReimbursementTest.java
git commit -m "feat(config): plafond de remboursement configurable yadony.reimbursement.max-amount-eur"
```

---

### Task A2 : Endpoint public `GET /config/reimbursement-cap`

**Files:**
- Create: `yadony-back/src/main/java/com/yadony/api/config/dto/ReimbursementCapResponse.java`
- Modify: `yadony-back/src/main/java/com/yadony/api/config/ConfigController.java`
- Test: `yadony-back/src/test/java/com/yadony/api/config/ConfigControllerReimbursementTest.java` (create)

**Interfaces:**
- Consumes: `YadonyConfigProperties.reimbursement().maxAmountEur()` (Task A1).
- Produces: `GET /config/reimbursement-cap` → 200 `{"maxAmountEur": <number>}`. Public (aucune auth).

- [ ] **Step 1 : Écrire le test d'intégration qui échoue**

Regarder d'abord un test existant du même style (`ConfigController` a un endpoint public déjà testé) pour copier le harnais MockMvc + `@ActiveProfiles("test")`. Create `yadony-back/src/test/java/com/yadony/api/config/ConfigControllerReimbursementTest.java` :

```java
package com.yadony.api.config;

import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConfigControllerReimbursementTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private FirebaseAuth firebaseAuth;

    @Test
    void reimbursementCapIsPublicAndReturnsConfiguredValue() throws Exception {
        mockMvc.perform(get("/config/reimbursement-cap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxAmountEur", notNullValue()));
    }
}
```

- [ ] **Step 2 : Lancer le test, vérifier l'échec**

Run: `cd yadony-back && ./mvnw test -Dtest=ConfigControllerReimbursementTest`
Expected: FAIL — 404 (endpoint absent).

- [ ] **Step 3 : Créer le DTO réponse**

Create `yadony-back/src/main/java/com/yadony/api/config/dto/ReimbursementCapResponse.java` :

```java
package com.yadony.api.config.dto;

import java.math.BigDecimal;

public record ReimbursementCapResponse(BigDecimal maxAmountEur) {}
```

- [ ] **Step 4 : Ajouter le mapping au `ConfigController`**

Dans `ConfigController.java`, ajouter l'import et la méthode. Le fichier complet devient :

```java
package com.yadony.api.config;

import com.yadony.api.config.dto.CommissionRateResponse;
import com.yadony.api.config.dto.ContentCategoryResponse;
import com.yadony.api.config.dto.ReimbursementCapResponse;
import com.yadony.api.config.dto.UrgencyThresholdResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/config")
public class ConfigController {

    private final YadonyConfigProperties config;

    public ConfigController(YadonyConfigProperties config) {
        this.config = config;
    }

    @GetMapping("/commission-rate")
    public ResponseEntity<CommissionRateResponse> getCommissionRate() {
        return ResponseEntity.ok(new CommissionRateResponse(config.commission().rate()));
    }

    @GetMapping("/urgency-threshold")
    public ResponseEntity<UrgencyThresholdResponse> getUrgencyThreshold() {
        return ResponseEntity.ok(new UrgencyThresholdResponse(config.urgency().thresholdDays()));
    }

    @GetMapping("/reimbursement-cap")
    public ResponseEntity<ReimbursementCapResponse> getReimbursementCap() {
        return ResponseEntity.ok(new ReimbursementCapResponse(config.reimbursement().maxAmountEur()));
    }

    @GetMapping("/content-categories")
    public ResponseEntity<List<ContentCategoryResponse>> getContentCategories() {
        return ResponseEntity.ok(ContentCatalog.CATEGORIES);
    }
}
```

- [ ] **Step 5 : Vérifier que `/config/reimbursement-cap` est bien public**

Chercher où `/config/**` (ou `/config/commission-rate`) est déclaré dans la config de sécurité :

Run: `cd yadony-back && rtk proxy grep -rn "/config" src/main/java/com/yadony/api/config/SecurityConfig.java`
Si `/config/**` est déjà `permitAll()`, aucun changement. Sinon, ajouter `/config/reimbursement-cap` à la liste des endpoints publics au même endroit que `/config/commission-rate`. (Le test de Step 1 échouerait sur 401/403 au lieu de 200 si l'endpoint n'est pas public — le test est le garde-fou.)

- [ ] **Step 6 : Lancer le test, vérifier le succès**

Run: `cd yadony-back && ./mvnw test -Dtest=ConfigControllerReimbursementTest`
Expected: PASS.

- [ ] **Step 7 : Commit**

```bash
cd yadony-back
git add src/main/java/com/yadony/api/config/dto/ReimbursementCapResponse.java \
        src/main/java/com/yadony/api/config/ConfigController.java \
        src/test/java/com/yadony/api/config/ConfigControllerReimbursementTest.java
git commit -m "feat(config): endpoint public GET /config/reimbursement-cap"
```

---

## Phase B — Backend : retrait complet de `declaredValueEur`

> Ordre imposé : d'abord neutraliser l'usage métier réel (MobileMoney, Task B1), puis retirer des DTOs/services (B2), puis des entités (B3), puis la colonne DB (B4), puis nettoyer les tests (B5). Le code ne compile pas entre B2 et B3 ; on ne lance `./mvnw test` qu'à la fin de B5.

### Task B1 : Neutraliser `MobileMoneyPaymentService.initiate()`

**Files:**
- Modify: `yadony-back/src/main/java/com/yadony/api/payments/mobilemoney/MobileMoneyPaymentService.java:56-115`
- Test: `yadony-back/src/test/java/com/yadony/api/payments/mobilemoney/MobileMoneyPaymentServiceTest.java` (create ou modifier si existant)

**Contexte :** `initiate()` lit `bid.getDeclaredValueEur()` comme montant de paiement Wave/Orange Money. Ce chemin est déjà bloqué à la création (`BidService.java:318-323` lève `mobile-money-bid-payment-retired`). On remplace la lecture du champ par la même exception 422 dès l'entrée de `initiate()`, cohérente avec la création. Aucun bid WAVE/ORANGE_MONEY en DB dev (vérifié).

- [ ] **Step 1 : Vérifier l'existence d'un test**

Run: `cd yadony-back && ls src/test/java/com/yadony/api/payments/mobilemoney/ 2>/dev/null`
Si `MobileMoneyPaymentServiceTest.java` existe, l'ouvrir pour adapter le test `initiate` existant ; sinon le créer.

- [ ] **Step 2 : Écrire le test qui échoue**

Create/adapter `yadony-back/src/test/java/com/yadony/api/payments/mobilemoney/MobileMoneyPaymentServiceTest.java` — un test vérifiant que `initiate` lève désormais 422 `mobile-money-bid-payment-retired` pour un bid WAVE, sans jamais toucher au gateway :

```java
package com.yadony.api.payments.mobilemoney;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MobileMoneyPaymentServiceTest {

    @Mock private MobileMoneyPaymentRepository repository;
    @Mock private MobileMoneyGatewayRegistry registry;
    @Mock private BidRepository bidRepository;
    @Mock private com.yadony.api.matching.AnnouncementRepository announcementRepository;
    @Mock private org.springframework.context.ApplicationEventPublisher events;
    @Mock private com.yadony.api.common.AuditService auditService;

    @InjectMocks private MobileMoneyPaymentService service;

    @Test
    void initiateAlwaysRejectsAsRetired() {
        UUID bidId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        BidEntity bid = new BidEntity();
        bid.setSenderId(senderId);
        bid.setPaymentMethod(PaymentMethod.WAVE);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> service.initiate(bidId, senderId))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("plus disponible");

        verifyNoInteractions(registry);
    }
}
```

- [ ] **Step 3 : Lancer le test, vérifier l'échec**

Run: `cd yadony-back && ./mvnw test -Dtest=MobileMoneyPaymentServiceTest`
Expected: FAIL (le service génère encore un lien de paiement au lieu de lever l'exception).

- [ ] **Step 4 : Remplacer le corps de `initiate()`**

Dans `MobileMoneyPaymentService.java`, remplacer tout le corps de la méthode `initiate` (lignes 55-115) par la version qui lève l'exception après le contrôle d'ownership, sans lire `declaredValueEur` :

```java
    /**
     * Le paiement mobile money direct par l'expéditeur a été retiré (cf.
     * BidService : 422 mobile-money-bid-payment-retired à la création). Cette
     * méthode reste exposée pour d'éventuels bids WAVE/ORANGE_MONEY legacy encore
     * PENDING : elle renvoie désormais la même erreur cohérente plutôt que de
     * générer un lien de paiement basé sur un champ supprimé.
     */
    @Transactional
    public MobileMoneyPaymentEntity initiate(UUID bidId, UUID callerId) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "bid-not-found", "Bid Not Found", "Offre introuvable : " + bidId));

        if (!bid.getSenderId().equals(callerId)) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "access-denied", "Access Denied",
                    "Vous n'êtes pas l'expéditeur de ce bid");
        }

        throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "mobile-money-bid-payment-retired", "Mobile Money Bid Payment Retired",
                "Le paiement mobile money direct par l'expéditeur n'est plus disponible.");
    }
```

Nettoyer les imports devenus inutiles (`BigDecimal`, `MobileMoneyGateway`, `MobileMoneyPaymentRequest`, `MobileMoneyLinkResult`, `LocalDateTime`, `ZoneOffset`, `Optional`) uniquement s'ils ne sont plus référencés ailleurs dans le fichier (`handleWebhook`/`getStatus` en utilisent encore certains — vérifier avant de retirer). Les méthodes `handleWebhook` et `getStatus` restent inchangées.

- [ ] **Step 5 : Lancer le test, vérifier le succès**

Run: `cd yadony-back && ./mvnw test -Dtest=MobileMoneyPaymentServiceTest`
Expected: PASS.

- [ ] **Step 6 : Commit**

```bash
cd yadony-back
git add src/main/java/com/yadony/api/payments/mobilemoney/MobileMoneyPaymentService.java \
        src/test/java/com/yadony/api/payments/mobilemoney/MobileMoneyPaymentServiceTest.java
git commit -m "refactor(payments): initiate mobile money renvoie l'erreur retired au lieu de lire declaredValueEur"
```

---

### Task B2 : Retirer `declaredValueEur` des DTOs, events et services

**Files (Modify) :**
- `yadony-back/src/main/java/com/yadony/api/matching/dto/BidRequest.java:18-21`
- `yadony-back/src/main/java/com/yadony/api/matching/dto/BidCheckoutRequest.java:17,34-40`
- `yadony-back/src/main/java/com/yadony/api/requests/dto/PackageRequestCompleteDetailsRequest.java:10-13`
- `yadony-back/src/main/java/com/yadony/api/matching/dto/BidResponse.java:25`
- `yadony-back/src/main/java/com/yadony/api/admin/dto/AdminBidDetailResponse.java:20`
- `yadony-back/src/main/java/com/yadony/api/admin/dto/AdminDisputeDetailResponse.java:12`
- `yadony-back/src/main/java/com/yadony/api/requests/event/PackageRequestAcceptedEvent.java:18`
- `yadony-back/src/main/java/com/yadony/api/requests/event/PackageRequestDetailsCompletedEvent.java:18`
- `yadony-back/src/main/java/com/yadony/api/matching/BidService.java:267-271,332,354,1133`
- `yadony-back/src/main/java/com/yadony/api/matching/BidCheckoutService.java:158-161,186`
- `yadony-back/src/main/java/com/yadony/api/matching/ThreadAcceptedBidListener.java:92,178`
- `yadony-back/src/main/java/com/yadony/api/requests/service/PackageRequestService.java:372,402`
- `yadony-back/src/main/java/com/yadony/api/requests/service/NegotiationService.java:879`
- `yadony-back/src/main/java/com/yadony/api/admin/AdminBidsController.java:164`
- `yadony-back/src/main/java/com/yadony/api/admin/AdminDisputesController.java:237`
- `yadony-back/src/main/java/com/yadony/api/admin/export/AdminExportService.java:104`

**Interfaces:**
- Produces (nouvelles signatures, consommées par les tests en Task B5) :
  - `BidRequest` sans `declaredValueEur` — dernier champ reste `gridItems`.
  - `BidCheckoutRequest` sans `declaredValueEur` — supprimer aussi le paramètre du constructeur historique.
  - `PackageRequestCompleteDetailsRequest(recipientName, recipientPhone, recipientCity)`.
  - `BidResponse` sans `declaredValueEur`.
  - `AdminBidDetailResponse` / `AdminDisputeDetailResponse` sans `declaredValueEur`.
  - `PackageRequestAcceptedEvent` / `PackageRequestDetailsCompletedEvent` sans `declaredValueEur`.

Ce sont des changements mécaniques (retrait d'un composant de record + de son usage). Détail par fichier :

- [ ] **Step 1 : `BidRequest.java`** — supprimer les lignes 18-21 (les 3 annotations `@NotNull/@DecimalMin/@DecimalMax` + `BigDecimal declaredValueEur,`). Retirer l'import `DecimalMax` et `DecimalMin` s'ils ne sont plus utilisés ailleurs dans le fichier (vérifier — d'autres champs peuvent les utiliser).

- [ ] **Step 2 : `BidCheckoutRequest.java`** — supprimer le composant `@NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal declaredValueEur,` (ligne 17) ET son usage dans le constructeur historique (lignes 34-40) : retirer le paramètre `BigDecimal declaredValueEur` de la signature du constructeur compact et l'argument correspondant dans l'appel `this(...)`. Retirer l'import `DecimalMin` si plus utilisé.

- [ ] **Step 3 : `PackageRequestCompleteDetailsRequest.java`** — supprimer les lignes 10-13 (validation + `BigDecimal declaredValueEur`). Le record devient :

```java
package com.yadony.api.requests.dto;

import jakarta.validation.constraints.*;

public record PackageRequestCompleteDetailsRequest(
    @NotBlank @Size(max = 100) String recipientName,
    @NotBlank @Pattern(regexp = "\\+[1-9]\\d{6,14}") String recipientPhone,
    @Size(max = 100) String recipientCity
) {}
```

- [ ] **Step 4 : `BidResponse.java`** — supprimer la ligne 25 `BigDecimal declaredValueEur,`.

- [ ] **Step 5 : `AdminBidDetailResponse.java`** — supprimer la ligne 20 `BigDecimal declaredValueEur,`.

- [ ] **Step 6 : `AdminDisputeDetailResponse.java`** — supprimer `BigDecimal declaredValueEur,` (ligne 12). Vérifier que l'import `BigDecimal` reste nécessaire (non — ce record n'a plus d'autre `BigDecimal` : retirer l'import `import java.math.BigDecimal;`).

- [ ] **Step 7 : `PackageRequestAcceptedEvent.java`** — supprimer la ligne 18 `BigDecimal declaredValueEur,`. Vérifier si `import java.math.BigDecimal;` reste utilisé (oui, `agreedPriceEur`/`weightKg` — garder).

- [ ] **Step 8 : `PackageRequestDetailsCompletedEvent.java`** — supprimer la ligne 18 `BigDecimal declaredValueEur,`. Retirer l'import `BigDecimal` s'il ne reste plus de champ de ce type (vérifier — plus aucun : retirer).

- [ ] **Step 9 : `BidService.java`** — trois retraits :
  - Lignes 267-271 : supprimer tout le bloc `if (request.declaredValueEur().compareTo(BigDecimal.valueOf(500)) > 0) { ... }`.
  - Ligne 332 : supprimer `bid.setDeclaredValueEur(request.declaredValueEur());`.
  - Ligne 354 : supprimer l'entrée `"declaredValueEur", saved.getDeclaredValueEur().toString(),` du `Map.of(...)` de l'audit log.
  - Ligne 1133 : supprimer l'argument `bid.getDeclaredValueEur(),` de l'appel `new BidResponse(...)`.

- [ ] **Step 10 : `BidCheckoutService.java`** — deux retraits :
  - Lignes 158-161 : supprimer le bloc `if (req.declaredValueEur().compareTo(BigDecimal.valueOf(500)) > 0) { ... }`.
  - Ligne 186 : supprimer `bid.setDeclaredValueEur(req.declaredValueEur());`.

- [ ] **Step 11 : `ThreadAcceptedBidListener.java`** — supprimer les deux `bid.setDeclaredValueEur(e.declaredValueEur());` (lignes 92 et 178).

- [ ] **Step 12 : `PackageRequestService.java`** — deux retraits :
  - Ligne 372 : supprimer `entity.setDeclaredValueEur(req.declaredValueEur());`.
  - Ligne 402 : supprimer l'argument `saved.getDeclaredValueEur(),` de la construction de `PackageRequestDetailsCompletedEvent` (vérifier la position exacte de l'argument dans l'appel et le retirer).

- [ ] **Step 13 : `NegotiationService.java`** — ligne 879 : supprimer l'argument `request.getDeclaredValueEur(),` de la construction de `new PackageRequestAcceptedEvent(...)`.

- [ ] **Step 14 : `AdminBidsController.java`** — ligne 164 : dans `new AdminBidDetailResponse(...)`, retirer l'argument `b.getDeclaredValueEur(),`. Le bloc devient :

```java
        return new AdminBidDetailResponse(
                item.id(), item.status(), item.announcementId(),
                item.senderName(), item.travelerName(), item.corridor(),
                item.weightKg(), item.netEur(), item.paymentMethod(), item.createdAt(),
                b.getContentCategory(), b.getRecipientName(),
                b.getTrackingNumber(), b.getCommissionRate(), b.getRefusalReason());
```

- [ ] **Step 15 : `AdminDisputesController.java`** — ligne 237 : retirer l'argument `d.getDeclaredValueEur(),` de la construction de `AdminDisputeDetailResponse`.

- [ ] **Step 16 : `AdminExportService.java`** — ligne 104 : retirer la ligne `money(d.getDeclaredValueEur()),` de l'appel `row(sb, ...)`. Vérifier la cohérence de l'en-tête CSV correspondant : chercher dans le même fichier le header qui liste les colonnes de dispute et retirer la colonne « valeur déclarée » associée pour que colonnes et valeurs restent alignées.

Run pour trouver l'en-tête : `cd yadony-back && rtk proxy grep -n "éclarée\|declared\|Declared" src/main/java/com/yadony/api/admin/export/AdminExportService.java`

- [ ] **Step 17 : Commit** (le code ne compile pas encore — entités pas touchées ; commit malgré tout pour isoler la couche DTO/service, la compilation sera verte après B3)

```bash
cd yadony-back
git add src/main/java/com/yadony/api/matching/dto/ \
        src/main/java/com/yadony/api/requests/dto/PackageRequestCompleteDetailsRequest.java \
        src/main/java/com/yadony/api/admin/dto/ \
        src/main/java/com/yadony/api/requests/event/ \
        src/main/java/com/yadony/api/matching/BidService.java \
        src/main/java/com/yadony/api/matching/BidCheckoutService.java \
        src/main/java/com/yadony/api/matching/ThreadAcceptedBidListener.java \
        src/main/java/com/yadony/api/requests/service/PackageRequestService.java \
        src/main/java/com/yadony/api/requests/service/NegotiationService.java \
        src/main/java/com/yadony/api/admin/AdminBidsController.java \
        src/main/java/com/yadony/api/admin/AdminDisputesController.java \
        src/main/java/com/yadony/api/admin/export/AdminExportService.java
git commit -m "refactor: retirer declaredValueEur des DTOs, events et services (compile après retrait entités)"
```

---

### Task B3 : Retirer le champ des entités JPA

**Files (Modify) :**
- `yadony-back/src/main/java/com/yadony/api/matching/BidEntity.java:33-34,215-216`
- `yadony-back/src/main/java/com/yadony/api/requests/entity/PackageRequestEntity.java:111-112,178,238`
- `yadony-back/src/main/java/com/yadony/api/disputes/DisputeEntity.java:45-46,81-82`

- [ ] **Step 1 : `BidEntity.java`** — supprimer le champ (lignes 33-34, `@Column(name = "declared_value_eur"...)` + `private BigDecimal declaredValueEur;`) et ses accesseurs (lignes 215-216, getter + setter). Laisser l'import `BigDecimal` (utilisé par `weightKg`, `negotiatedNetEur`, etc.).

- [ ] **Step 2 : `PackageRequestEntity.java`** — supprimer le champ (lignes 111-112), le getter (ligne 178) et le setter (ligne 238). Laisser l'import `BigDecimal` (utilisé par `weightKg`, `targetPriceEur`, lat/lng).

- [ ] **Step 3 : `DisputeEntity.java`** — supprimer le champ (lignes 45-46) et ses accesseurs (lignes 81-82). Retirer l'import `import java.math.BigDecimal;` s'il n'y a plus d'autre champ `BigDecimal` (vérifier — plus aucun : retirer).

- [ ] **Step 4 : Vérifier la compilation du main**

Run: `cd yadony-back && ./mvnw -q compile`
Expected: BUILD SUCCESS (plus aucune référence à `declaredValueEur` dans le main). Si erreur, un site d'appel a été oublié — le corriger avant de continuer.

- [ ] **Step 5 : Commit**

```bash
cd yadony-back
git add src/main/java/com/yadony/api/matching/BidEntity.java \
        src/main/java/com/yadony/api/requests/entity/PackageRequestEntity.java \
        src/main/java/com/yadony/api/disputes/DisputeEntity.java
git commit -m "refactor: retirer le champ declaredValueEur des entités JPA"
```

---

### Task B4 : Migration Flyway V184 — drop des colonnes

**Files:**
- Create: `yadony-back/src/main/resources/db/migration/V184__drop_declared_value_eur.sql`

- [ ] **Step 1 : Créer la migration**

Create `yadony-back/src/main/resources/db/migration/V184__drop_declared_value_eur.sql` :

```sql
-- Retrait de la valeur déclarée du colis : remplacée côté produit par une
-- politique de remboursement informative (plafond configurable yadony.reimbursement).
-- Le champ n'était utilisé que pour affichage + une validation ≤ 500 € ; aucune
-- logique métier (escrow, litige, remboursement) ne s'y appuyait. Le paiement
-- mobile money qui lisait ce champ comme montant est déjà retiré (cf. BidService).
-- IF EXISTS : rejoue sûr, aligné sur le style de V181.
ALTER TABLE bids DROP COLUMN IF EXISTS declared_value_eur;
ALTER TABLE package_requests DROP COLUMN IF EXISTS declared_value_eur;
ALTER TABLE disputes DROP COLUMN IF EXISTS declared_value_eur;
```

- [ ] **Step 2 : Vérifier que la migration s'applique sur base propre**

Run: `cd yadony-back && ./mvnw test -Dtest=YadonyConfigPropertiesReimbursementTest`
(N'importe quel `@SpringBootTest` avec profil test déclenche Flyway sur la base de test H2/PostgreSQL et validera que V184 s'applique sans erreur. Si un test `@SpringBootTest` échoue au démarrage Flyway, corriger la migration.)
Expected: PASS (démarrage Flyway sans erreur `Migration failed`).

- [ ] **Step 3 : Commit**

```bash
cd yadony-back
git add src/main/resources/db/migration/V184__drop_declared_value_eur.sql
git commit -m "feat(db): V184 drop colonne declared_value_eur (bids, package_requests, disputes)"
```

---

### Task B5 : Nettoyer les tests backend référençant `declaredValueEur`

**Files (Modify) :**
- `yadony-back/src/test/java/com/yadony/api/matching/BidServiceTest.java`
- `yadony-back/src/test/java/com/yadony/api/matching/BidCreateGuardTest.java`
- `yadony-back/src/test/java/com/yadony/api/matching/dto/BidCheckoutRequestTest.java`
- `yadony-back/src/test/java/com/yadony/api/matching/BidCheckoutControllerIntegrationTest.java`
- `yadony-back/src/test/java/com/yadony/api/matching/BidCheckoutServiceTest.java`
- `yadony-back/src/test/java/com/yadony/api/requests/service/PackageRequestServiceTest.java`
- `yadony-back/src/test/java/com/yadony/api/matching/ThreadAcceptedBidListenerTest.java`
- `yadony-back/src/test/java/com/yadony/api/notifications/RequestEventsListenerTest.java`
- `yadony-back/src/test/java/com/yadony/api/requests/service/NegotiationServiceTest.java`
- `yadony-back/src/test/java/com/yadony/api/migrations/V171ContentCategoriesMigrationTest.java`
- `yadony-back/src/test/java/com/yadony/api/e2e/steps/BidSteps.java`
- `yadony-back/src/test/java/com/yadony/api/e2e/steps/RequestsSteps.java`

**Pattern de nettoyage (mécanique, identique partout) :**
1. Constructeurs de records (`new BidRequest(...)`, `new BidCheckoutRequest(...)`, `new PackageRequestCompleteDetailsRequest(...)`, `new PackageRequestAcceptedEvent(...)`, `new PackageRequestDetailsCompletedEvent(...)`) : retirer l'argument `declaredValueEur` positionnel (souvent une valeur `BigDecimal` type `new BigDecimal("100")` ou `BigDecimal.valueOf(...)`). Attention à l'ordre positionnel — retirer le bon argument.
2. Assertions sur `declaredValueEur` / `getDeclaredValueEur()` / `jsonPath("$.declaredValueEur")` : supprimer.
3. Tests dédiés à la règle « valeur > 500 → 422 » (ex. `testCreateBid_DeclaredValueTooHigh_Returns422`, `value-exceeds-limit`) : supprimer le test entier (la règle n'existe plus).
4. Setup SQL / INSERT de tests migration insérant `declared_value_eur` : retirer la colonne et sa valeur.

**Restauration vs suppression** (mémoire `feedback_restore_not_delete_tests`) : ici on ne supprime pas des tests d'un code encore vivant — on adapte des tests à une signature qui a changé et on retire les tests d'une règle métier délibérément supprimée. C'est légitime. Ne PAS supprimer un test qui échoue pour une autre raison sans comprendre pourquoi.

- [ ] **Step 1 : Localiser chaque occurrence**

Run: `cd yadony-back && rtk proxy grep -rn "declaredValueEur\|declared_value_eur\|value-exceeds-limit\|DeclaredValueTooHigh\|getDeclaredValueEur" src/test/java`

- [ ] **Step 2 : Traiter chaque fichier** selon le pattern ci-dessus. Pour les 4 fichiers signalés par la recherche initiale (`V171ContentCategoriesMigrationTest`, `NegotiationServiceTest`, `BidSteps`, `RequestsSteps`) plus les fichiers de sites d'appel de constructeurs (`BidServiceTest`, `BidCreateGuardTest`, `BidCheckoutRequestTest`, `BidCheckoutControllerIntegrationTest`, `BidCheckoutServiceTest`, `PackageRequestServiceTest`, `ThreadAcceptedBidListenerTest`, `RequestEventsListenerTest`), appliquer les retraits.

- [ ] **Step 3 : Lancer toute la suite backend**

Run: `cd yadony-back && ./mvnw test`
Expected: BUILD SUCCESS, 0 échec. Corriger les compilations restantes / assertions cassées jusqu'au vert.

> Piège connu (mémoire `backend_test_jvm_oom`) : un `Exit 134` / SIGABRT avec 0 échec de test = manque de RAM JVM, pas une régression — relancer, ne pas chercher un bug. Ne jamais lancer `./mvnw compile` pendant que `./mvnw test` tourne.

- [ ] **Step 4 : Vérifier la couverture ≥ 90 %**

Run: `cd yadony-back && ./mvnw test jacoco:report`
Ouvrir `target/site/jacoco/index.html`. Si < 90 %, ajouter des tests sur le nouveau code (config + endpoint sont déjà couverts par A1/A2 ; le reste est du retrait donc la couverture ne devrait pas baisser).

- [ ] **Step 5 : Commit**

```bash
cd yadony-back
git add src/test/java
git commit -m "test: adapter les tests au retrait de declaredValueEur"
```

---

## Phase C — Flutter : fetch + cache du plafond de remboursement

### Task C1 : Datasource + repository `getReimbursementCap()`

**Files:**
- Modify: `yadony_app/lib/features/config/data/config_datasource.dart`
- Modify: `yadony_app/lib/features/config/data/config_repository.dart`
- Test: `yadony_app/test/features/config/config_repository_test.dart` (create ou modifier si existant)

**Interfaces:**
- Produces: `ConfigDatasource.getReimbursementCap()` → `Future<double>` (parse `response.data['maxAmountEur']`). `IConfigRepository.getReimbursementCap()` → `Future<double>`.

- [ ] **Step 1 : Écrire le test repo qui échoue**

Vérifier d'abord s'il existe `yadony_app/test/features/config/config_repository_test.dart` : `cd yadony_app && ls test/features/config/ 2>/dev/null`. Adapter s'il existe, sinon créer. Le test mocke `ConfigDatasource` et vérifie que `getReimbursementCap()` délègue :

```dart
import 'package:yadony/features/config/data/config_datasource.dart';
import 'package:yadony/features/config/data/config_repository.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

class _MockDatasource extends Mock implements ConfigDatasource {}

void main() {
  late _MockDatasource datasource;
  late ConfigRepository repository;

  setUp(() {
    datasource = _MockDatasource();
    repository = ConfigRepository(datasource);
  });

  test('getReimbursementCap delegates to datasource', () async {
    when(() => datasource.getReimbursementCap()).thenAnswer((_) async => 50.0);
    expect(await repository.getReimbursementCap(), 50.0);
    verify(() => datasource.getReimbursementCap()).called(1);
  });
}
```

(Adapter le framework de mock à celui utilisé dans le repo — vérifier `mocktail` vs `mockito` dans un test config existant.)

- [ ] **Step 2 : Lancer le test, vérifier l'échec**

Run: `cd yadony_app && flutter test test/features/config/config_repository_test.dart`
Expected: FAIL — `getReimbursementCap` n'existe pas.

- [ ] **Step 3 : Ajouter la méthode au datasource**

Dans `config_datasource.dart`, ajouter après `getUrgencyThresholdDays()` :

```dart
  Future<double> getReimbursementCap() async {
    final response = await _client.dio.get('/config/reimbursement-cap');
    final data = response.data as Map<String, dynamic>;
    return (data['maxAmountEur'] as num).toDouble();
  }
```

- [ ] **Step 4 : Ajouter au repository (interface + impl)**

Dans `config_repository.dart`, ajouter à `abstract class IConfigRepository` :

```dart
  Future<double> getReimbursementCap();
```

et à `ConfigRepository` :

```dart
  @override
  Future<double> getReimbursementCap() async {
    try {
      return await _datasource.getReimbursementCap();
    } catch (e) {
      throw unwrapDioError(e);
    }
  }
```

- [ ] **Step 5 : Lancer le test, vérifier le succès**

Run: `cd yadony_app && flutter test test/features/config/config_repository_test.dart`
Expected: PASS.

- [ ] **Step 6 : Commit**

```bash
cd yadony_app
git add lib/features/config/data/config_datasource.dart \
        lib/features/config/data/config_repository.dart \
        test/features/config/config_repository_test.dart
git commit -m "feat(config): getReimbursementCap dans datasource et repository"
```

---

### Task C2 : Cache global `yadonyReimbursementCapEur` + init au démarrage

**Files:**
- Modify: `yadony_app/lib/core/pricing/yadony_pricing.dart`
- Modify: `yadony_app/lib/main.dart` (autour de la ligne 144, là où `setYadonyCommissionRate` est appelé)
- Test: `yadony_app/test/core/pricing/yadony_pricing_reimbursement_test.dart` (create)

**Interfaces:**
- Produces: `kYadonyReimbursementCapDefault` (const double = 50), `yadonyReimbursementCapEur` (getter double), `setYadonyReimbursementCap(double)`, `yadonyReimbursementCapLabel` (String, entier si rond sinon 2 décimales virgule FR).

- [ ] **Step 1 : Écrire le test qui échoue**

Create `yadony_app/test/core/pricing/yadony_pricing_reimbursement_test.dart` :

```dart
import 'package:yadony/core/pricing/yadony_pricing.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('default reimbursement cap is 50', () {
    expect(yadonyReimbursementCapEur, kYadonyReimbursementCapDefault);
    expect(yadonyReimbursementCapEur, 50.0);
  });

  test('setYadonyReimbursementCap accepts positive values', () {
    setYadonyReimbursementCap(75);
    expect(yadonyReimbursementCapEur, 75.0);
    expect(yadonyReimbursementCapLabel, '75');
  });

  test('setYadonyReimbursementCap ignores non-positive values', () {
    setYadonyReimbursementCap(75);
    setYadonyReimbursementCap(0);
    setYadonyReimbursementCap(-5);
    expect(yadonyReimbursementCapEur, 75.0);
  });

  test('label uses French comma for decimals', () {
    setYadonyReimbursementCap(49.5);
    expect(yadonyReimbursementCapLabel, '49,5');
  });
}
```

- [ ] **Step 2 : Lancer le test, vérifier l'échec**

Run: `cd yadony_app && flutter test test/core/pricing/yadony_pricing_reimbursement_test.dart`
Expected: FAIL (symboles inexistants).

- [ ] **Step 3 : Ajouter le cache à `yadony_pricing.dart`**

Ajouter à la fin de `yadony_pricing.dart` (avant l'extension, ou après — hors de l'extension) :

```dart
/// Plafond de remboursement yadony en cas de perte de colis (€), source unique
/// backend `yadony.reimbursement.max-amount-eur`. Chargé une fois au démarrage
/// via `GET /config/reimbursement-cap` → [setYadonyReimbursementCap], repli sur
/// [kYadonyReimbursementCapDefault] tant qu'il n'est pas chargé / en cas d'erreur.
const double kYadonyReimbursementCapDefault = 50;

double _yadonyReimbursementCapEur = kYadonyReimbursementCapDefault;

/// Plafond de remboursement courant en euros.
double get yadonyReimbursementCapEur => _yadonyReimbursementCapEur;

/// Libellé du plafond (entier si rond, sinon 2 décimales max, virgule FR).
/// À interpoler dans les textes UI : `'$yadonyReimbursementCapLabel €'`.
String get yadonyReimbursementCapLabel {
  final v = _yadonyReimbursementCapEur;
  return v % 1 == 0
      ? v.toStringAsFixed(0)
      : v.toStringAsFixed(2).replaceFirst(RegExp(r'0+$'), '').replaceFirst('.', ',');
}

/// Met à jour le plafond au démarrage avec la valeur backend. Ignore les
/// valeurs non strictement positives (repli sur le défaut).
void setYadonyReimbursementCap(double amount) {
  if (amount > 0) _yadonyReimbursementCapEur = amount;
}
```

- [ ] **Step 4 : Lancer le test, vérifier le succès**

Run: `cd yadony_app && flutter test test/core/pricing/yadony_pricing_reimbursement_test.dart`
Expected: PASS.

- [ ] **Step 5 : Charger la valeur au démarrage dans `main.dart`**

Le pattern réel (vérifié) : dans `_bootstrap()`, chaque valeur de config est chargée par une fonction dédiée `_loadXxx()` lancée en `unawaited(...)`, non bloquante. Reproduire à l'identique pour le plafond.

Dans `_bootstrap()`, juste après la ligne `unawaited(_loadUrgencyThreshold());` (L139), ajouter :

```dart
  // Plafond de remboursement Yadony (SOURCE UNIQUE : yadony.reimbursement.max-amount-eur
  // côté backend) : chargé une fois pour que le banner + la FAQ suivent
  // automatiquement. Non bloquant.
  unawaited(_loadReimbursementCap());
```

Puis, à côté de `_loadUrgencyThreshold()` (après L158), ajouter la fonction :

```dart
Future<void> _loadReimbursementCap() async {
  try {
    setYadonyReimbursementCap(
      await getIt<IConfigRepository>().getReimbursementCap(),
    );
  } catch (_) {
    // Repli sur kYadonyReimbursementCapDefault conservé — non bloquant.
  }
}
```

`setYadonyReimbursementCap` vient de `yadony_pricing.dart`, déjà importé (`setYadonyCommissionRate` en provient). `IConfigRepository` déjà importé.

> **Écart assumé vs spec (YAGNI) :** la spec §3 listait `config_bloc.dart` / `config_event.dart` / `config_state.dart` à modifier. Vérifié : le démarrage charge la config via le repository directement (`getIt<IConfigRepository>()`), pas via `ConfigBloc`. Le bloc n'est pas sur ce chemin, on ne le touche donc pas.

- [ ] **Step 6 : Vérifier l'analyse statique**

Run: `cd yadony_app && flutter analyze lib/main.dart lib/core/pricing/yadony_pricing.dart`
Expected: No issues.

- [ ] **Step 7 : Commit**

```bash
cd yadony_app
git add lib/core/pricing/yadony_pricing.dart lib/main.dart \
        test/core/pricing/yadony_pricing_reimbursement_test.dart
git commit -m "feat(pricing): cache global du plafond de remboursement + chargement au démarrage"
```

---

## Phase D — Flutter : retrait du champ, banner, chaîne, affichages, FAQ

### Task D1 : Widget banner réutilisable `ReimbursementInfoBanner`

**Files:**
- Create: `yadony_app/lib/features/matching/presentation/widgets/reimbursement_info_banner.dart`
- Test: `yadony_app/test/features/matching/presentation/widgets/reimbursement_info_banner_test.dart` (create)

**Interfaces:**
- Produces: `ReimbursementInfoBanner` (StatelessWidget, const constructor) — affiche icône info + texte court avec `yadonyReimbursementCapLabel`, et un lien « Voir conditions » qui `context.push` vers la FAQ (ou ouvre la section). Réutilisé par D2 (create_bid) et D3 (complete_details).

- [ ] **Step 1 : Écrire le widget test qui échoue**

Create `yadony_app/test/features/matching/presentation/widgets/reimbursement_info_banner_test.dart` :

```dart
import 'package:yadony/core/pricing/yadony_pricing.dart';
import 'package:yadony/features/matching/presentation/widgets/reimbursement_info_banner.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('shows configured reimbursement cap and conditions link',
      (tester) async {
    setYadonyReimbursementCap(50);
    await tester.pumpWidget(const MaterialApp(
      home: Scaffold(body: ReimbursementInfoBanner()),
    ));
    expect(find.textContaining('50'), findsWidgets);
    expect(find.textContaining('rembourse'), findsOneWidget);
    expect(find.textContaining('conditions'), findsOneWidget);
  });
}
```

(Ajuster les `textContaining` au wording final si besoin. Si le lien navigue via GoRouter, le test peut se contenter de vérifier la présence du texte « conditions » sans router complet.)

- [ ] **Step 2 : Lancer le test, vérifier l'échec**

Run: `cd yadony_app && flutter test test/features/matching/presentation/widgets/reimbursement_info_banner_test.dart`
Expected: FAIL (widget inexistant).

- [ ] **Step 3 : Créer le widget**

Create `yadony_app/lib/features/matching/presentation/widgets/reimbursement_info_banner.dart`. Utiliser les tokens theme-aware (`Theme.of(context).colorScheme`), s'inspirer du style du bloc info existant en haut du formulaire (« Ces suggestions sont les contenus acceptés... », icône `Icons.info_outline`). Texte sans em-dash. Le lien « Voir conditions » navigue vers la route FAQ (vérifier la route exacte dans `router.dart` : `cd yadony_app && rtk proxy grep -n "faq\|Faq\|FAQ" lib/app/router.dart`) ; à défaut de route dédiée, le lien peut être un simple `Text` souligné inerte dans une première version, mais préférer la navigation si la route existe.

```dart
import 'package:yadony/core/pricing/yadony_pricing.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

/// Bandeau informatif remplaçant l'ancien champ « valeur déclarée ». Explique
/// la politique de remboursement yadony (plafond configurable, sous conditions,
/// jamais automatique) et renvoie vers le détail des conditions (FAQ).
class ReimbursementInfoBanner extends StatelessWidget {
  const ReimbursementInfoBanner({super.key, this.onSeeConditions});

  /// Optionnel : override de l'action « Voir conditions » (sinon navigue FAQ).
  final VoidCallback? onSeeConditions;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: cs.outlineVariant),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.info_outline, size: 20, color: cs.onSurfaceVariant),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'En cas de perte confirmée après recherche, yadony rembourse '
                  "jusqu'à $yadonyReimbursementCapLabel € sous conditions.",
                  style: textTheme.bodySmall?.copyWith(color: cs.onSurfaceVariant),
                ),
                const SizedBox(height: 6),
                GestureDetector(
                  onTap: onSeeConditions ??
                      () => context.push('/profile/faq'),
                  child: Text(
                    'Voir conditions',
                    style: textTheme.bodySmall?.copyWith(
                      color: cs.primary,
                      fontWeight: FontWeight.w600,
                      decoration: TextDecoration.underline,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
```

(Remplacer `/profile/faq` par la route réelle trouvée à l'étape ci-dessus. Si aucune route FAQ n'existe, retirer le `GestureDetector` de navigation et laisser seulement le texte informatif pour cette tâche, la FAQ étant traitée en D6.)

- [ ] **Step 4 : Lancer le test, vérifier le succès**

Run: `cd yadony_app && flutter test test/features/matching/presentation/widgets/reimbursement_info_banner_test.dart`
Expected: PASS.

- [ ] **Step 5 : Commit**

```bash
cd yadony_app
git add lib/features/matching/presentation/widgets/reimbursement_info_banner.dart \
        test/features/matching/presentation/widgets/reimbursement_info_banner_test.dart
git commit -m "feat(matching): widget ReimbursementInfoBanner (remplace le champ valeur déclarée)"
```

---

### Task D2 : Retirer `declaredValue` de la chaîne bid (event → bloc → datasource → model)

**Files (Modify) :**
- `yadony_app/lib/features/matching/bloc/bid_event.dart`
- `yadony_app/lib/features/matching/bloc/bid_bloc.dart`
- `yadony_app/lib/features/matching/data/repositories/bid_repository.dart`
- `yadony_app/lib/features/matching/data/datasources/bid_remote_datasource.dart`
- `yadony_app/lib/features/matching/data/models/bid_model.dart` (+ régénérer `bid_model.g.dart`)

**Interfaces:**
- Produces : `BidCreateRequested` et `BidCheckoutRequested` sans le paramètre `declaredValueEur` ; méthodes repo/datasource sans ce paramètre ; `BidModel` sans le champ `declaredValueEur` (ni parsing JSON).

- [ ] **Step 1 : Localiser précisément**

Run: `cd yadony_app && rtk proxy grep -n "declaredValue" lib/features/matching/bloc/bid_event.dart lib/features/matching/bloc/bid_bloc.dart lib/features/matching/data/repositories/bid_repository.dart lib/features/matching/data/datasources/bid_remote_datasource.dart lib/features/matching/data/models/bid_model.dart`

- [ ] **Step 2 : `bid_event.dart`** — retirer le champ `declaredValueEur` (et son `required this.declaredValueEur`) des events `BidCreateRequested` et `BidCheckoutRequested`.

- [ ] **Step 3 : `bid_bloc.dart`** — dans les handlers `_onCreateRequested` / `_onCheckoutRequested` (ou équivalents), retirer le passage de `event.declaredValueEur` aux appels repository. Vérifier aussi les events analytics du bloc (`bid_submitted`) : s'ils incluaient une propriété dérivée de la valeur déclarée, la retirer (règle PII analytics interdit la valeur exacte de toute façon).

- [ ] **Step 4 : `bid_repository.dart`** — retirer le paramètre `declaredValueEur` des méthodes de création/checkout et de leur transmission au datasource.

- [ ] **Step 5 : `bid_remote_datasource.dart`** — retirer la clé `'declaredValueEur'` du body JSON (`data`/`FormData`) envoyé à `/bids` et `/bids/checkout`.

- [ ] **Step 6 : `bid_model.dart`** — retirer le champ `declaredValueEur`, son annotation `@JsonKey` éventuelle, le paramètre du constructeur, et toute utilisation dans `copyWith`/`props` (Equatable). Puis régénérer :

Run: `cd yadony_app && flutter pub run build_runner build --delete-conflicting-outputs`
Vérifier que `bid_model.g.dart` ne référence plus `declaredValueEur`.

- [ ] **Step 7 : Vérifier l'analyse statique**

Run: `cd yadony_app && flutter analyze lib/features/matching`
Expected: pas d'erreur liée à `declaredValue` (des erreurs subsisteront dans les écrans tant que D3/D4 pas faits — les traiter dans leurs tâches ; ici on vise la couche data/bloc propre).

- [ ] **Step 8 : Commit**

```bash
cd yadony_app
git add lib/features/matching/bloc/bid_event.dart \
        lib/features/matching/bloc/bid_bloc.dart \
        lib/features/matching/data/repositories/bid_repository.dart \
        lib/features/matching/data/datasources/bid_remote_datasource.dart \
        lib/features/matching/data/models/bid_model.dart \
        lib/features/matching/data/models/bid_model.g.dart
git commit -m "refactor(matching): retirer declaredValue de la chaîne bid event→bloc→datasource→model"
```

---

### Task D3 : `create_bid_bottom_sheet.dart` — retrait champ + banner

**Files:**
- Modify: `yadony_app/lib/features/matching/presentation/widgets/create_bid_bottom_sheet.dart`

**Références à traiter** (relevé rtk) : `_valueCtrl` déclaré L116 ; `_formSignature` L145 ; `_dirtySources` L263 ; `dispose` L310 ; validation `_goToPicker` L450-458 ; `_CollectedFormData` L57/67 ; `BidCreateRequested`/`BidCheckoutRequested` L481/508/522 ; UI champ L902-931.

- [ ] **Step 1 : Retirer le contrôleur et ses références de plomberie**
  - L116 : supprimer `final _valueCtrl = TextEditingController();`.
  - L145 : retirer `_valueCtrl.text,` de `_formSignature`.
  - L263 : retirer `_valueCtrl,` de `_dirtySources`.
  - L310 : retirer `_valueCtrl.dispose();`.

- [ ] **Step 2 : Retirer la validation valeur déclarée dans `_goToPicker`** — supprimer le bloc L450-458 :

```dart
    final val = double.tryParse(_valueCtrl.text);
    if (val == null || val <= 0) {
      _showError('Valeur déclarée invalide');
      return;
    }
    if (val > 500) {
      _showError('Valeur maximum : 500 €');
      return;
    }
```

- [ ] **Step 3 : Retirer `declaredValueEur` de `_CollectedFormData`** — supprimer L57 (`required this.declaredValueEur,`) et L67 (`final double declaredValueEur;`). Puis dans la construction `_formData = _CollectedFormData(...)` (L479-489), retirer `declaredValueEur: val,` (L481).

- [ ] **Step 4 : Retirer `declaredValueEur` des events** — dans `_confirmPayment` (L499-531), retirer `declaredValueEur: data.declaredValueEur,` des deux appels `BidCreateRequested` (L508) et `BidCheckoutRequested` (L522). (Cohérent avec D2 qui a retiré le paramètre de l'event.)

- [ ] **Step 5 : Remplacer le bloc UI par le banner** — remplacer les lignes 902-931 (label `VALEUR DÉCLARÉE (€)` + `TextFormField` + `SizedBox`) par :

```dart
            // ── Politique de remboursement ────────────────────────────────
            const ReimbursementInfoBanner()
                .animate().fadeIn(delay: 140.ms),
            const SizedBox(height: YadonySpacing.xxl),
```

Ajouter l'import en tête du fichier :

```dart
import 'package:yadony/features/matching/presentation/widgets/reimbursement_info_banner.dart';
```

- [ ] **Step 6 : Vérifier l'analyse statique**

Run: `cd yadony_app && flutter analyze lib/features/matching/presentation/widgets/create_bid_bottom_sheet.dart`
Expected: No issues.

- [ ] **Step 7 : Commit**

```bash
cd yadony_app
git add lib/features/matching/presentation/widgets/create_bid_bottom_sheet.dart
git commit -m "feat(matching): retirer le champ valeur déclarée de l'envoi de colis, afficher le banner remboursement"
```

---

### Task D4 : `complete_details_screen.dart` + `complete_details_bloc.dart` + repo package_request

**Files:**
- Modify: `yadony_app/lib/features/package_request/presentation/screens/sender/complete_details_screen.dart`
- Modify: `yadony_app/lib/features/package_request/bloc/complete_details_bloc.dart`
- Modify: `yadony_app/lib/features/package_request/data/package_request_repository.dart`

- [ ] **Step 1 : Localiser**

Run: `cd yadony_app && rtk proxy grep -n "declaredValue\|valeur déclarée\|VALEUR DÉCLARÉE\|500" lib/features/package_request/presentation/screens/sender/complete_details_screen.dart lib/features/package_request/bloc/complete_details_bloc.dart lib/features/package_request/data/package_request_repository.dart`

- [ ] **Step 2 : `complete_details_bloc.dart`** — retirer le champ `declaredValueEur` de l'event de soumission, du state si présent, et de la construction du body/appel repo.

- [ ] **Step 3 : `package_request_repository.dart`** — retirer le paramètre `declaredValueEur` et la clé JSON `'declaredValueEur'` du body envoyé à `POST /package-requests/{id}/complete-details` (endpoint qui mappe `PackageRequestCompleteDetailsRequest`, désormais sans ce champ côté backend — Task B2 Step 3).

- [ ] **Step 4 : `complete_details_screen.dart`** — retirer le contrôleur de valeur déclarée (déclaration, dispose, validation, passage à l'event) et remplacer le bloc UI du champ par `const ReimbursementInfoBanner()` (mêmes ajustements que D3, avec l'import). Suivre le style de section de cet écran.

- [ ] **Step 5 : Vérifier l'analyse statique**

Run: `cd yadony_app && flutter analyze lib/features/package_request`
Expected: No issues liés à declaredValue.

- [ ] **Step 6 : Commit**

```bash
cd yadony_app
git add lib/features/package_request/presentation/screens/sender/complete_details_screen.dart \
        lib/features/package_request/bloc/complete_details_bloc.dart \
        lib/features/package_request/data/package_request_repository.dart
git commit -m "feat(package_request): retirer le champ valeur déclarée de complete-details, afficher le banner"
```

---

### Task D5 : Retirer l'affichage lecture seule (colis_card, colis_destinataire_card, billet_talon)

**Files:**
- Modify: `yadony_app/lib/features/matching/presentation/widgets/colis_card.dart`
- Modify: `yadony_app/lib/features/matching/presentation/widgets/bid_detail/colis_destinataire_card.dart`
- Modify: `yadony_app/lib/features/matching/presentation/widgets/billet/billet_talon.dart`

- [ ] **Step 1 : Localiser chaque affichage**

Run: `cd yadony_app && rtk proxy grep -n "declaredValue" lib/features/matching/presentation/widgets/colis_card.dart lib/features/matching/presentation/widgets/bid_detail/colis_destinataire_card.dart lib/features/matching/presentation/widgets/billet/billet_talon.dart`

- [ ] **Step 2 : Retirer chaque ligne/bloc d'affichage** de la valeur déclarée (label + valeur). `BidModel.declaredValueEur` n'existe plus (D2) — ces widgets ne compileraient pas sinon. Retirer proprement la ligne de détail (souvent un `_DetailRow('Valeur déclarée', ...)` ou équivalent) sans casser la mise en page (retirer aussi un éventuel séparateur orphelin).

- [ ] **Step 3 : Vérifier l'analyse statique**

Run: `cd yadony_app && flutter analyze lib/features/matching/presentation/widgets`
Expected: No issues.

- [ ] **Step 4 : Commit**

```bash
cd yadony_app
git add lib/features/matching/presentation/widgets/colis_card.dart \
        lib/features/matching/presentation/widgets/bid_detail/colis_destinataire_card.dart \
        lib/features/matching/presentation/widgets/billet/billet_talon.dart
git commit -m "refactor(matching): retirer l'affichage de la valeur déclarée (carte colis, destinataire, talon)"
```

---

### Task D6 : Réécrire l'entrée FAQ avec les conditions de remboursement

**Files:**
- Modify: `yadony_app/lib/features/profile/presentation/screens/faq_screen.dart` (~L103)

- [ ] **Step 1 : Localiser l'entrée**

Run: `cd yadony_app && sed -n '90,120p' lib/features/profile/presentation/screens/faq_screen.dart`
Identifier l'entrée FAQ mentionnant l'ancien plafond 500 € / assurance / valeur déclarée.

- [ ] **Step 2 : Réécrire question + réponse**

Remplacer le texte par la politique de remboursement complète, montant interpolé depuis `yadonyReimbursementCapLabel` (jamais codé en dur). Question : « Que se passe-t-il si mon colis est perdu ? ». Réponse (adapter au format de données de la FAQ, souvent un `(question:, answer:)` ou un widget expansion) :

```
yadony ne couvre pas automatiquement la perte d'un colis. En cas de perte confirmée après recherche de notre équipe, un remboursement jusqu'à $yadonyReimbursementCapLabel € peut être accordé si toutes les conditions suivantes sont respectées : paiement effectué par carte via yadony (jamais en espèces) ; aucun échange ou paiement effectué en dehors de la plateforme avec le voyageur ; colis scanné via QR code yadony au dépôt et à la remise ; litige signalé dans l'application dans les 15 jours suivant la date de livraison prévue ; contenu du colis conforme aux objets autorisés par yadony. Le remboursement n'est jamais automatique et reste soumis à validation de l'équipe yadony après investigation.
```

Ajouter l'import `yadony_pricing.dart` si absent. Attention : si les entrées FAQ sont des `const`, la valeur interpolée dynamiquement casse le `const` — retirer le `const` de cette entrée (piège connu, cf. mémoire dark-mode migration : « retirer const contenant une valeur dynamique »). Ne pas utiliser d'em-dash dans le texte (déjà des points-virgules ici).

- [ ] **Step 3 : Vérifier l'analyse statique**

Run: `cd yadony_app && flutter analyze lib/features/profile/presentation/screens/faq_screen.dart`
Expected: No issues.

- [ ] **Step 4 : Commit**

```bash
cd yadony_app
git add lib/features/profile/presentation/screens/faq_screen.dart
git commit -m "feat(profile): réécrire la FAQ perte de colis avec les conditions de remboursement"
```

---

### Task D7 : Nettoyer et compléter les tests Flutter

**Files (Modify — tests référençant declaredValue) :**
- `yadony_app/test/features/matching/data/datasources/bid_remote_datasource_test.dart`
- `yadony_app/test/features/matching/data/bid_model_test.dart`
- `yadony_app/test/features/matching/data/repositories/bid_repository_test.dart`
- `yadony_app/test/features/matching/data/bid_repository_photos_test.dart`
- `yadony_app/test/features/matching/presentation/screens/shipment_list_screen_aurora_test.dart`
- `yadony_app/test/features/matching/presentation/widgets/create_bid_bottom_sheet_recipient_test.dart`
- `yadony_app/test/features/matching/presentation/widgets/create_bid_bottom_sheet_cash_test.dart`
- `yadony_app/test/features/matching/presentation/widgets/near_me_carousel_test.dart`
- `yadony_app/test/features/matching/presentation/widgets/billet/billet_talon_test.dart`
- `yadony_app/test/features/matching/presentation/widgets/bid_detail/sender_detail_body_test.dart`
- `yadony_app/test/features/matching/presentation/widgets/bid_detail/sender_cards_test.dart`
- `yadony_app/test/features/matching/bloc/bid_bloc_test.dart`
- `yadony_app/test/features/matching/bloc/bid_bloc_analytics_test.dart`
- `yadony_app/test/features/payments/presentation/screens/payment_screen_test.dart`
- `yadony_app/test/features/package_request/data/package_request_repository_test.dart`
- `yadony_app/test/features/package_request/presentation/screens/sender/complete_details_screen_test.dart`
- `yadony_app/test/features/package_request/bloc/complete_details_bloc_test.dart`
- `yadony_app/test/features/profile/presentation/screens/shipments_history_screen_test.dart`
- `yadony_app/test/a11y/large_text_smoke_test.dart`

**Pattern de nettoyage :**
1. Fixtures `BidModel(...)` / JSON de fixtures : retirer le champ `declaredValueEur` / clé `'declaredValueEur'`.
2. Events `BidCreateRequested(...)` / `BidCheckoutRequested(...)` dans `blocTest` : retirer l'argument `declaredValueEur:`.
3. Assertions sur affichage de la valeur déclarée (`find.text('120 €')` etc.) : supprimer.
4. Tests remplissant `_valueCtrl` via `tester.enterText(...)` sur le champ valeur déclarée : retirer ces étapes (le champ n'existe plus). Les tests des widgets `create_bid` doivent viser le banner à la place quand pertinent.
5. Tests d'assertion body datasource (`verify` que `data['declaredValueEur']` est envoyé) : retirer.

- [ ] **Step 1 : Localiser toutes les occurrences**

Run: `cd yadony_app && rtk proxy grep -rn "declaredValue\|valeur déclarée\|VALEUR DÉCLARÉE" test/`

- [ ] **Step 2 : Traiter chaque fichier** selon le pattern. Ne pas supprimer un test entier sauf s'il testait spécifiquement le champ valeur déclarée ou la règle 500 € (délibérément retirés). Adapter les autres.

- [ ] **Step 3 : Ajouter un widget test vérifiant l'absence du champ + présence du banner** (dans `create_bid_bottom_sheet_recipient_test.dart` ou un nouveau fichier) :

```dart
testWidgets('le champ valeur déclarée est absent, le banner remboursement est présent',
    (tester) async {
  // ... pump du CreateBidScreen avec les providers/mocks habituels du fichier
  expect(find.text('VALEUR DÉCLARÉE (€)'), findsNothing);
  expect(find.byType(ReimbursementInfoBanner), findsOneWidget);
});
```

(S'aligner sur le harnais de pump déjà utilisé dans le fichier — providers BidBloc/PaymentBloc/BidPhotosCubit mockés.)

- [ ] **Step 4 : Lancer toute la suite Flutter**

Run: `cd yadony_app && flutter test`
Expected: All tests passed. Corriger les échecs restants jusqu'au vert. (Ne jamais lancer deux commandes Flutter en parallèle.)

- [ ] **Step 5 : Vérifier la couverture ≥ 90 %**

Run: `cd yadony_app && flutter test --coverage`
Puis inspecter `coverage/lcov.info` (ou `genhtml`). Si le nouveau code (banner, pricing, config) descend sous 90 %, ajouter des tests ciblés.

- [ ] **Step 6 : Commit**

```bash
cd yadony_app
git add test/
git commit -m "test: adapter les tests au retrait du champ valeur déclarée + banner remboursement"
```

---

## Vérification finale

- [ ] Backend : `cd yadony-back && ./mvnw test` → 0 rouge, JaCoCo ≥ 90 %.
- [ ] Flutter : `cd yadony_app && flutter analyze && flutter test --coverage` → 0 rouge, ≥ 90 %.
- [ ] `rtk proxy grep -rn "declaredValue\|declared_value" yadony-back/src yadony_app/lib` → plus aucune occurrence hors migration V184 (drop) et éventuels commentaires historiques.
- [ ] Vérif manuelle device (optionnel, si device dispo) : formulaire « Envoyer un colis » n'affiche plus le champ valeur déclarée mais le banner ; « Voir conditions » ouvre la FAQ ; FAQ affiche le plafond configuré.
- [ ] Docs de story backend/front si la convention l'exige (dossiers `docs/stories-done/`).

---

## Notes de portée (découvertes pendant le levage de plan)

Ampleur réelle plus large que la spec initiale, points ajoutés :
- `MobileMoneyPaymentService` lisait `declaredValueEur` comme **montant de paiement** (pas juste affichage) — chemin legacy déjà retiré à la création, neutralisé proprement en B1.
- Chaîne Flutter complète event→bloc→repository→datasource→model à nettoyer (D2), pas seulement l'UI.
- Surfaces d'affichage supplémentaires : `billet_talon.dart` (talon), en plus de `colis_card` et `colis_destinataire_card`.
- Export CSV admin (`AdminExportService`) : retirer la colonne pour garder l'alignement header/valeurs.
- Décision confirmée : plafond **informatif uniquement**, aucun enforcement backend sur le guarantee-fund admin (inchangé).
