# Brouillons de demandes et dépublication — plan backend

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Donner aux demandes d'envoi un statut `DRAFT` avec publication différée, et permettre de dépublier une demande ou un trajet tant qu'aucun tiers ne s'est engagé.

**Architecture:** Le trajet (`AnnouncementService`) a déjà résolu le problème du brouillon ; la demande (`PackageRequestService`) reprend le même découpage — `saveAsDraft` à la création, endpoint `/publish` qui rejoue toutes les validations, brouillon invisible des tiers. La dépublication est ajoutée symétriquement aux deux domaines comme une transition inverse gardée par « zéro engagement reçu ».

**Tech Stack:** Spring Boot 3.4 / Java 21, PostgreSQL 16 + Flyway, JUnit 5 + Mockito + AssertJ, MockMvc pour l'intégration, EmbeddedPostgres (zonky) pour les tests de migration.

**Spec:** `docs/superpowers/specs/2026-08-01-brouillons-demandes-depublication-design.md`

## Global Constraints

- Branche de travail : `feature/brouillons-demandes-depublication`. **Ne jamais commiter sur `main`.**
- Jamais de `Co-Authored-By: Claude` dans les messages de commit.
- Jamais modifier une migration Flyway existante — toujours créer V(n+1). La dernière est `V184`.
- Toute erreur remonte en `ResponseStatusException` avec un code métier en `reason` (`request/...`, `announcement/...`), jamais de String ou Map brute.
- Toute action métier significative écrit dans `audit_log` via `auditService.log(...)`.
- Soft delete uniquement — jamais de DELETE physique.
- Pas d'injection de service inter-package : communication par Spring Events.
- `./mvnw test` doit passer à 0 rouge avant chaque commit. Couverture ≥ 90 %.
- **Ne jamais lancer `./mvnw compile` pendant qu'un `./mvnw test` tourne** — cela produit de faux 401/403 sur des endpoints sans rapport.
- Un `Exit 134` / SIGABRT avec 0 échec est un manque de RAM de la JVM, pas une régression.

## File Structure

**Créés :**
- `src/main/resources/db/migration/V185__package_requests_draft_status.sql` — ajoute `DRAFT` à la contrainte CHECK du statut
- `src/test/java/com/yadony/api/migrations/V185DraftStatusMigrationTest.java` — vérifie que la contrainte accepte `DRAFT` et rejette toujours un statut inconnu

**Modifiés :**
- `src/main/java/com/yadony/api/requests/entity/PackageRequestStatus.java` — `+ DRAFT`
- `src/main/java/com/yadony/api/requests/dto/PackageRequestCreateRequest.java` — `+ Boolean saveAsDraft`
- `src/main/java/com/yadony/api/requests/repository/PackageRequestRepository.java` — `+ countBySenderIdAndStatus`
- `src/main/java/com/yadony/api/requests/service/PackageRequestService.java` — création conditionnelle, `publish`, `unpublish`, `update` qui préserve le brouillon, `getById` qui masque le brouillon
- `src/main/java/com/yadony/api/requests/controller/PackageRequestController.java` — `+ POST /{id}/publish`, `+ POST /{id}/unpublish`
- `src/main/java/com/yadony/api/matching/AnnouncementService.java` — `+ unpublishAnnouncement`
- `src/main/java/com/yadony/api/matching/AnnouncementController.java` — `+ POST /{id}/unpublish`
- `src/test/java/com/yadony/api/requests/service/PackageRequestServiceTest.java` — construction du service (14e paramètre) + nouveaux tests
- `src/test/java/com/yadony/api/requests/controller/PackageRequestControllerIT.java` — tests des deux nouveaux endpoints
- `src/test/java/com/yadony/api/matching/AnnouncementServiceTest.java` — tests de dépublication

---

### Task 1 : statut `DRAFT` en base et dans l'enum

**Files:**
- Create: `src/main/resources/db/migration/V185__package_requests_draft_status.sql`
- Create: `src/test/java/com/yadony/api/migrations/V185DraftStatusMigrationTest.java`
- Modify: `src/main/java/com/yadony/api/requests/entity/PackageRequestStatus.java`

**Interfaces:**
- Produces: `PackageRequestStatus.DRAFT` — valeur d'enum consommée par toutes les tâches suivantes.

- [ ] **Step 1: Écrire le test de migration**

Le profil `test` tourne sur H2 avec Flyway désactivé ; les tests de migration démarrent donc un PostgreSQL embarqué. On migre jusqu'à V184, on vérifie que `DRAFT` est refusé, puis on applique V185 et on vérifie qu'il passe.

Créer `src/test/java/com/yadony/api/migrations/V185DraftStatusMigrationTest.java` :

```java
package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V185 — ajout de DRAFT à la contrainte CHECK du statut des demandes.
 *
 * <p>La colonne est un VARCHAR(20) sous contrainte CHECK explicite
 * (chk_pkg_req_status, cf. V57) : ajouter une valeur à l'enum Java ne suffit
 * pas, la base rejetterait l'insertion. Ce test verrouille les deux sens —
 * DRAFT accepté après migration, valeur inconnue toujours rejetée.
 */
class V185DraftStatusMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDb() throws Exception {
        postgres = EmbeddedPostgres.start();
        dataSource = postgres.getPostgresDatabase();
    }

    @AfterAll
    static void stopDb() throws Exception {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void resetSchema() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
    }

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    /** Insère une demande minimale avec le statut demandé. */
    private void insertRequestWithStatus(String status) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                INSERT INTO package_requests
                  (id, sender_id, departure_city, arrival_city, desired_date,
                   date_tolerance_days, weight_kg, parcel_size, content_category,
                   negotiable, status, created_at, updated_at)
                VALUES
                  ('%s', '%s', 'Paris', 'Dakar', CURRENT_DATE + 10,
                   2, 3.0, 'MEDIUM', 'Documents',
                   true, '%s', now(), now())
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), status));
        }
    }

    @Test
    void beforeV185_draftStatusIsRejected() {
        migrateTo("184");
        assertThatThrownBy(() -> insertRequestWithStatus("DRAFT"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_pkg_req_status");
    }

    @Test
    void afterV185_draftStatusIsAccepted() throws Exception {
        migrateTo("185");
        insertRequestWithStatus("DRAFT");

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            var rs = st.executeQuery(
                    "SELECT count(*) FROM package_requests WHERE status = 'DRAFT'");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void afterV185_existingStatusesStillAccepted() throws Exception {
        migrateTo("185");
        for (String s : new String[]{"OPEN", "NEGOTIATING", "ACCEPTED",
                                     "EXPIRED", "CANCELLED", "COMPLETED"}) {
            insertRequestWithStatus(s);
        }
    }

    @Test
    void afterV185_unknownStatusStillRejected() {
        migrateTo("185");
        assertThatThrownBy(() -> insertRequestWithStatus("PUBLISHED"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_pkg_req_status");
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./mvnw test -Dtest=V185DraftStatusMigrationTest`
Expected: FAIL — `afterV185_draftStatusIsAccepted` échoue car la migration V185 n'existe pas (Flyway s'arrête à V184 et la contrainte rejette `DRAFT`).

- [ ] **Step 3: Écrire la migration**

Créer `src/main/resources/db/migration/V185__package_requests_draft_status.sql` :

```sql
-- Ajoute le statut DRAFT aux demandes d'envoi.
--
-- Une demande pouvait seulement naître publiée : la contrainte CHECK posée en
-- V57 n'admet pas d'état antérieur à la publication. DRAFT ouvre deux usages —
-- préparer une demande et la publier plus tard, et dépublier une demande sans
-- l'annuler (annuler est terminal).
--
-- La contrainte est recréée plutôt qu'assouplie : garder une liste fermée de
-- statuts valides est ce qui protège la colonne d'une faute de frappe côté
-- applicatif.

ALTER TABLE package_requests
  DROP CONSTRAINT IF EXISTS chk_pkg_req_status;

ALTER TABLE package_requests
  ADD CONSTRAINT chk_pkg_req_status CHECK (
    status IN ('DRAFT', 'OPEN', 'NEGOTIATING', 'ACCEPTED',
               'EXPIRED', 'CANCELLED', 'COMPLETED')
  );
```

- [ ] **Step 4: Ajouter la valeur à l'enum Java**

Modifier `src/main/java/com/yadony/api/requests/entity/PackageRequestStatus.java` :

```java
package com.yadony.api.requests.entity;

public enum PackageRequestStatus {
    DRAFT, OPEN, NEGOTIATING, ACCEPTED, EXPIRED, CANCELLED, COMPLETED
}
```

- [ ] **Step 5: Lancer le test pour vérifier qu'il passe**

Run: `./mvnw test -Dtest=V185DraftStatusMigrationTest`
Expected: PASS — 4 tests verts.

- [ ] **Step 6: Vérifier qu'aucun `switch` exhaustif n'est cassé**

Ajouter une valeur à un enum casse les `switch` exhaustifs qui ne la couvrent pas.

Run: `./mvnw test`
Expected: PASS. Si un `switch` sur `PackageRequestStatus` refuse de compiler, ajouter la branche `DRAFT` avec le comportement du statut `OPEN` **sauf** pour tout ce qui concerne la visibilité publique, où `DRAFT` doit se comporter comme un statut non listé.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V185__package_requests_draft_status.sql \
        src/main/java/com/yadony/api/requests/entity/PackageRequestStatus.java \
        src/test/java/com/yadony/api/migrations/V185DraftStatusMigrationTest.java
git commit -m "feat(requests): ajoute le statut DRAFT aux demandes d'envoi"
```

---

### Task 2 : création d'une demande en brouillon

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/dto/PackageRequestCreateRequest.java`
- Modify: `src/main/java/com/yadony/api/requests/repository/PackageRequestRepository.java`
- Modify: `src/main/java/com/yadony/api/requests/service/PackageRequestService.java:94-193`
- Test: `src/test/java/com/yadony/api/requests/service/PackageRequestServiceTest.java`

**Interfaces:**
- Consumes: `PackageRequestStatus.DRAFT` (Task 1).
- Produces:
  - `PackageRequestCreateRequest.saveAsDraft()` → `Boolean` (nullable, `null` ⇒ publication directe)
  - `PackageRequestRepository.countBySenderIdAndStatus(UUID senderId, PackageRequestStatus status)` → `long`
  - Champ `PackageRequestService.yadonyConfig` de type `YadonyConfigProperties` (14e paramètre du constructeur, en dernière position)

- [ ] **Step 1: Ajouter le champ au DTO**

Modifier `src/main/java/com/yadony/api/requests/dto/PackageRequestCreateRequest.java` — ajouter en dernier champ du record, après `photoKeys` :

```java
    // Clés S3 des photos colis (max 4) — sous package_requests/{senderId}/. Remplace photoUrl.
    @Size(max = 4) List<String> photoKeys,
    // null/false → publication directe (comportement historique) ; true → brouillon.
    // Nullable et non primitif pour que l'absence du champ reste distincte de false.
    Boolean saveAsDraft
) {}
```

- [ ] **Step 2: Ajouter la méthode de comptage au repository**

Modifier `src/main/java/com/yadony/api/requests/repository/PackageRequestRepository.java` — ajouter à côté de `countBySenderIdAndStatusIn` (ligne 20) :

```java
    /** Brouillons d'un expéditeur — plafonné par yadony.limits.drafts (free/PRO). */
    long countBySenderIdAndStatus(UUID senderId, PackageRequestStatus status);
```

- [ ] **Step 3: Écrire les tests qui échouent**

Ajouter dans `PackageRequestServiceTest`, à la suite des `@Nested` existants :

```java
    // ========== Brouillons ==========

    /** Construit une requête de création valide ; saveAsDraft piloté par l'appelant. */
    private PackageRequestCreateRequest draftRequest(Boolean saveAsDraft) {
        return new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(10), 2,
                new BigDecimal("3.0"), "Documents", null, null, null, null, null,
                true, EnumSet.of(PaymentMethod.STRIPE), null, saveAsDraft);
    }

    @Nested @DisplayName("create() — brouillon")
    class CreateDraft {

        @BeforeEach
        void stubSave() {
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> {
                PackageRequestEntity e = inv.getArgument(0);
                setId(e, UUID.randomUUID());
                return e;
            });
        }

        @Test @DisplayName("saveAsDraft=true → statut DRAFT, aucun event, aucun disclaimer")
        void create_asDraft_doesNotPublish() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatus(SENDER_ID, PackageRequestStatus.DRAFT))
                    .thenReturn(0L);

            service.create(SENDER_ID, draftRequest(true));

            ArgumentCaptor<PackageRequestEntity> captor =
                    ArgumentCaptor.forClass(PackageRequestEntity.class);
            verify(repository).save(captor.capture());
            PackageRequestEntity saved = captor.getValue();

            assertThat(saved.getStatus()).isEqualTo(PackageRequestStatus.DRAFT);
            // Le disclaimer douanier se signe à la publication, pas à la rédaction.
            assertThat(saved.getDisclaimerSignedAt()).isNull();
            // L'event déclenche les alertes corridor : le publier notifierait une
            // demande que personne ne peut voir.
            verify(eventPublisher, never()).publishEvent(any(PackageRequestCreatedEvent.class));
            verify(auditService).log(eq("PACKAGE_REQUEST"), any(), eq("DRAFT_CREATED"),
                    eq(SENDER_ID), any());
        }

        @Test @DisplayName("brouillon : KYC non vérifié accepté")
        void create_asDraft_doesNotRequireKyc() {
            sender.setKycStatus(KycStatus.PENDING);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatus(SENDER_ID, PackageRequestStatus.DRAFT))
                    .thenReturn(0L);

            assertThatCode(() -> service.create(SENDER_ID, draftRequest(true)))
                    .doesNotThrowAnyException();
        }

        @Test @DisplayName("brouillon : ne compte pas dans maxOpenRequestsPerSender")
        void create_asDraft_ignoresOpenQuota() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatus(SENDER_ID, PackageRequestStatus.DRAFT))
                    .thenReturn(0L);

            service.create(SENDER_ID, draftRequest(true));

            verify(repository, never()).countBySenderIdAndStatusIn(any(), any());
        }

        @Test @DisplayName("limite de brouillons atteinte → 403 draft-limit-reached")
        void create_asDraft_overLimit_throws403() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            // maxDrafts() vaut 1 par défaut quand yadony.limits.drafts n'est pas configuré.
            when(repository.countBySenderIdAndStatus(SENDER_ID, PackageRequestStatus.DRAFT))
                    .thenReturn(1L);

            assertThatThrownBy(() -> service.create(SENDER_ID, draftRequest(true)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("draft-limit-reached");
        }

        @Test @DisplayName("saveAsDraft=null → publication directe (comportement historique)")
        void create_nullFlag_publishesDirectly() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);

            service.create(SENDER_ID, draftRequest(null));

            ArgumentCaptor<PackageRequestEntity> captor =
                    ArgumentCaptor.forClass(PackageRequestEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(PackageRequestStatus.OPEN);
            verify(eventPublisher).publishEvent(any(PackageRequestCreatedEvent.class));
        }
    }
```

Note : `assertThatCode` doit être importé — il est déjà couvert par le `import static org.assertj.core.api.Assertions.*;` en tête de fichier.

- [ ] **Step 4: Lancer les tests pour vérifier qu'ils échouent**

Run: `./mvnw test -Dtest=PackageRequestServiceTest`
Expected: FAIL — compilation impossible (`draftRequest` passe 15 arguments, `countBySenderIdAndStatus` inconnu du mock tant que le repository ne l'expose pas) puis échecs sur le statut.

- [ ] **Step 5: Injecter la configuration des limites dans le service**

Modifier `src/main/java/com/yadony/api/requests/service/PackageRequestService.java`.

Le champ existant `config` est un `RequestsConfig` ; le nouveau bean prend donc un nom distinct.

Ajouter l'import :

```java
import com.yadony.api.config.YadonyConfigProperties;
```

Ajouter le champ après `matchingService` (ligne 61) :

```java
    private final YadonyConfigProperties yadonyConfig;
```

Ajouter le paramètre en **dernière** position du constructeur et l'affectation correspondante :

```java
                                  MatchingService matchingService,
                                  YadonyConfigProperties yadonyConfig) {
        ...
        this.matchingService = matchingService;
        this.yadonyConfig = yadonyConfig;
    }
```

- [ ] **Step 6: Rendre la création conditionnelle**

Toujours dans `PackageRequestService`, remplacer le bloc de validation et de pose du statut dans `createAndReturnEntity` (lignes 128-193).

Remplacer le contrôle KYC (lignes 132-134) et le contrôle de quota (lignes 146-150) par :

```java
        boolean isDraft = Boolean.TRUE.equals(req.saveAsDraft());

        // Un brouillon n'est pas publié : ni KYC ni quota de demandes ouvertes ne
        // s'appliquent encore. Les deux sont rejoués à la publication.
        if (!isDraft) {
            if (sender.getKycStatus() != KycStatus.VERIFIED) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "kyc/not-verified");
            }
            long openCount = repository.countBySenderIdAndStatusIn(senderId,
                List.of(PackageRequestStatus.OPEN, PackageRequestStatus.NEGOTIATING));
            if (openCount >= config.maxOpenRequestsPerSender()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "request/max-open-reached");
            }
        } else {
            assertDraftQuotaAvailable(senderId, sender);
        }
```

Les validations métier (`rejectMobileMoneyMethods`, budget si prix ferme, corridor, date) restent **hors** de ce `if` : elles s'appliquent aux deux chemins.

Remplacer la pose du statut et du disclaimer (lignes 177-180) par :

```java
        entity.setStatus(isDraft ? PackageRequestStatus.DRAFT : PackageRequestStatus.OPEN);
        // Le disclaimer douanier est accepté à la publication. Tant que la demande
        // est un brouillon, l'expéditeur n'a rien publié — donc rien signé.
        if (!isDraft) {
            entity.setDisclaimerSignedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
```

Remplacer la publication de l'event et l'audit (lignes 185-190) par :

```java
        if (isDraft) {
            auditService.log("PACKAGE_REQUEST", saved.getId(), "DRAFT_CREATED", senderId,
                Map.of("corridor", saved.getDepartureCity() + "->" + saved.getArrivalCity()));
        } else {
            eventPublisher.publishEvent(new PackageRequestCreatedEvent(
                saved.getId(), senderId, saved.getDepartureCity(),
                saved.getArrivalCity(), saved.getDesiredDate()
            ));
            auditService.log("PACKAGE_REQUEST", saved.getId(), "CREATED", senderId,
                Map.of("corridor", saved.getDepartureCity() + "->" + saved.getArrivalCity()));
        }
```

- [ ] **Step 7: Ajouter le garde-fou de quota de brouillons**

Ajouter cette méthode privée dans `PackageRequestService`, sous `createAndReturnEntity` :

```java
    /**
     * Plafonne les brouillons d'un expéditeur au même quota que les trajets
     * (yadony.limits.drafts) : un utilisateur a un quota de brouillons, pas un
     * quota par type d'objet. Appelé aussi à la dépublication, sans quoi
     * dépublier deviendrait un moyen de contourner le plafond.
     */
    private void assertDraftQuotaAvailable(UUID senderId, UserEntity sender) {
        YadonyConfigProperties.Limits limits = yadonyConfig.limits() != null
            ? yadonyConfig.limits()
            : new YadonyConfigProperties.Limits(null, null);
        int maxDrafts = sender.isProAccount() ? limits.maxDraftsPro() : limits.maxDrafts();
        long draftCount = repository.countBySenderIdAndStatus(senderId, PackageRequestStatus.DRAFT);
        if (draftCount >= maxDrafts) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "draft-limit-reached");
        }
    }
```

- [ ] **Step 8: Mettre à jour la construction du service dans les tests**

Le constructeur prend un 14e paramètre. Modifier `PackageRequestServiceTest` ligne 100-103 — le champ `yadonyConfig` existe déjà dans la classe de test :

```java
        service = new PackageRequestService(
                repository, userRepository, eventPublisher, auditService, config,
                threadRepository, cityRepository, commissionProperties,
                storageService, photoService, favoriteRepository, realMapper, matchingService,
                yadonyConfig);
```

Faire la même correction dans tout autre test qui instancie `PackageRequestService` :

Run: `grep -rn "new PackageRequestService(" src/test/`

- [ ] **Step 9: Lancer les tests pour vérifier qu'ils passent**

Run: `./mvnw test -Dtest=PackageRequestServiceTest`
Expected: PASS — anciens tests toujours verts (le chemin `saveAsDraft=null` est le comportement historique), 5 nouveaux tests verts.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/yadony/api/requests/ src/test/java/com/yadony/api/requests/
git commit -m "feat(requests): permet d'enregistrer une demande en brouillon"
```

---

### Task 3 : publication d'un brouillon

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/service/PackageRequestService.java`
- Modify: `src/main/java/com/yadony/api/requests/controller/PackageRequestController.java`
- Test: `src/test/java/com/yadony/api/requests/service/PackageRequestServiceTest.java`
- Test: `src/test/java/com/yadony/api/requests/controller/PackageRequestControllerIT.java`

**Interfaces:**
- Consumes: `PackageRequestStatus.DRAFT` (Task 1), `assertDraftQuotaAvailable` (Task 2).
- Produces: `PackageRequestService.publish(UUID callerUid, UUID requestId)` → `PackageRequestResponse` ; endpoint `POST /package-requests/{id}/publish`.

- [ ] **Step 1: Écrire les tests de service qui échouent**

Ajouter dans `PackageRequestServiceTest` :

```java
    @Nested @DisplayName("publish()")
    class Publish {

        private PackageRequestEntity draft(UUID id) {
            PackageRequestEntity e = new PackageRequestEntity();
            setId(e, id);
            e.setSenderId(SENDER_ID);
            e.setDepartureCity("Paris");
            e.setArrivalCity("Dakar");
            e.setDesiredDate(LocalDate.now().plusDays(10));
            e.setDateToleranceDays((short) 2);
            e.setWeightKg(new BigDecimal("3.0"));
            e.setContentCategory("Documents");
            e.setNegotiable(true);
            e.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
            e.setStatus(PackageRequestStatus.DRAFT);
            return e;
        }

        @Test @DisplayName("DRAFT → OPEN + event + disclaimer signé + audit PUBLISHED")
        void publish_draft_becomesOpen() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = draft(id);
            when(repository.findById(id)).thenReturn(Optional.of(e));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(i -> i.getArgument(0));

            service.publish(SENDER_ID, id);

            assertThat(e.getStatus()).isEqualTo(PackageRequestStatus.OPEN);
            assertThat(e.getDisclaimerSignedAt()).isNotNull();
            verify(eventPublisher).publishEvent(any(PackageRequestCreatedEvent.class));
            verify(auditService).log(eq("PACKAGE_REQUEST"), eq(id), eq("PUBLISHED"),
                    eq(SENDER_ID), any());
        }

        @Test @DisplayName("non-propriétaire → 404 (ne révèle pas l'existence)")
        void publish_notOwner_throws404() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(draft(id)));

            assertThatThrownBy(() -> service.publish(UUID.randomUUID(), id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("request/not-found");
        }

        @Test @DisplayName("déjà publiée → 409 request/not-draft")
        void publish_alreadyOpen_throws409() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = draft(id);
            e.setStatus(PackageRequestStatus.OPEN);
            when(repository.findById(id)).thenReturn(Optional.of(e));

            assertThatThrownBy(() -> service.publish(SENDER_ID, id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("request/not-draft");
        }

        @Test @DisplayName("KYC non vérifié → 403 kyc/not-verified")
        void publish_kycNotVerified_throws403() {
            UUID id = UUID.randomUUID();
            sender.setKycStatus(KycStatus.PENDING);
            when(repository.findById(id)).thenReturn(Optional.of(draft(id)));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            assertThatThrownBy(() -> service.publish(SENDER_ID, id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("kyc/not-verified");
        }

        @Test @DisplayName("date devenue trop lointaine → 422 request/date-too-far")
        void publish_dateTooFar_throws422() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = draft(id);
            e.setDesiredDate(LocalDate.now().plusDays(120));
            when(repository.findById(id)).thenReturn(Optional.of(e));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            assertThatThrownBy(() -> service.publish(SENDER_ID, id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("request/date-too-far");
        }

        @Test @DisplayName("quota de demandes ouvertes atteint → 409 max-open-reached")
        void publish_overOpenQuota_throws409() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(draft(id)));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(config.maxOpenRequestsPerSender()).thenReturn(1);
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(1L);

            assertThatThrownBy(() -> service.publish(SENDER_ID, id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("request/max-open-reached");
        }
    }
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./mvnw test -Dtest=PackageRequestServiceTest`
Expected: FAIL — `publish` n'existe pas, compilation impossible.

- [ ] **Step 3: Implémenter `publish`**

Ajouter dans `PackageRequestService`, après `update` :

```java
    // ─── publish ─────────────────────────────────────────────────────────────────

    /**
     * Publie un brouillon (DRAFT → OPEN).
     *
     * <p>Toutes les validations de publication sont rejouées et non supposées
     * acquises à la création : les données ont pu être modifiées depuis, et une
     * date sort naturellement de la fenêtre autorisée avec le temps.
     */
    @Transactional
    public PackageRequestResponse publish(UUID callerUid, UUID requestId) {
        PackageRequestEntity entity = repository.findById(requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        // 404 et non 403 : un brouillon est invisible des tiers, répondre « interdit »
        // révélerait son existence.
        if (!entity.getSenderId().equals(callerUid)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found");
        }
        if (entity.getStatus() != PackageRequestStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/not-draft");
        }

        UserEntity sender = userRepository.findById(callerUid)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        if (sender.getKycStatus() != KycStatus.VERIFIED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "kyc/not-verified");
        }
        if (entity.getDepartureCity().equalsIgnoreCase(entity.getArrivalCity())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "request/invalid-corridor");
        }
        if (entity.getDesiredDate().isAfter(LocalDate.now().plusDays(90))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "request/date-too-far");
        }
        if (!entity.isNegotiable() && entity.getTargetPriceEur() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "request/target-price-required-firm");
        }
        long openCount = repository.countBySenderIdAndStatusIn(callerUid,
            List.of(PackageRequestStatus.OPEN, PackageRequestStatus.NEGOTIATING));
        if (openCount >= config.maxOpenRequestsPerSender()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/max-open-reached");
        }

        entity.setStatus(PackageRequestStatus.OPEN);
        entity.setDisclaimerSignedAt(LocalDateTime.now(ZoneOffset.UTC));
        PackageRequestEntity saved = repository.save(entity);

        eventPublisher.publishEvent(new PackageRequestCreatedEvent(
            saved.getId(), callerUid, saved.getDepartureCity(),
            saved.getArrivalCity(), saved.getDesiredDate()
        ));
        auditService.log("PACKAGE_REQUEST", saved.getId(), "PUBLISHED", callerUid,
            Map.of("corridor", saved.getDepartureCity() + "->" + saved.getArrivalCity()));

        return toResponse(saved);
    }
```

Vérifier que `KycStatus` et `UserEntity` sont importés ; sinon ajouter :

```java
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
```

- [ ] **Step 4: Lancer les tests de service pour vérifier qu'ils passent**

Run: `./mvnw test -Dtest=PackageRequestServiceTest`
Expected: PASS.

- [ ] **Step 5: Exposer l'endpoint**

Modifier `src/main/java/com/yadony/api/requests/controller/PackageRequestController.java` — ajouter après `update` (ligne 82) :

```java
    /** Publie un brouillon (DRAFT → OPEN) après avoir rejoué tous les contrôles. */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('SENDER')")
    public PackageRequestResponse publish(@PathVariable UUID id) {
        return service.publish(requireUserId(), id);
    }
```

- [ ] **Step 6: Écrire le test d'intégration**

Ajouter dans `PackageRequestControllerIT` — adapter les helpers d'authentification au style déjà présent dans ce fichier (le token Firebase y est déjà mocké) :

```java
    @Test
    @DisplayName("POST /package-requests/{id}/publish — brouillon du propriétaire → 200 + OPEN")
    void publish_ownDraft_returns200() throws Exception {
        UUID id = createDraftForCurrentSender();

        mockMvc.perform(post("/package-requests/" + id + "/publish")
                        .header("Authorization", "Bearer " + senderToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    @DisplayName("POST /package-requests/{id}/publish — brouillon d'autrui → 404")
    void publish_othersDraft_returns404() throws Exception {
        UUID id = createDraftForOtherSender();

        mockMvc.perform(post("/package-requests/" + id + "/publish")
                        .header("Authorization", "Bearer " + senderToken()))
                .andExpect(status().isNotFound());
    }
```

Les helpers `createDraftForCurrentSender()`, `createDraftForOtherSender()` et `senderToken()` s'écrivent en reprenant les helpers de création déjà présents dans le fichier, en posant `saveAsDraft=true` dans le payload.

- [ ] **Step 7: Lancer les tests d'intégration**

Run: `./mvnw test -Dtest=PackageRequestControllerIT`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/yadony/api/requests/ src/test/java/com/yadony/api/requests/
git commit -m "feat(requests): expose la publication d'un brouillon de demande"
```

---

### Task 4 : l'édition d'un brouillon ne le publie pas

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/service/PackageRequestService.java:215-260`
- Test: `src/test/java/com/yadony/api/requests/service/PackageRequestServiceTest.java`

**Interfaces:**
- Consumes: `PackageRequestStatus.DRAFT` (Task 1).
- Produces: rien de nouveau — corrige le comportement de `update`.

- [ ] **Step 1: Écrire les tests qui échouent**

Ajouter dans `PackageRequestServiceTest` :

```java
    @Nested @DisplayName("update() — brouillon")
    class UpdateDraft {

        @Test @DisplayName("éditer un brouillon le laisse DRAFT")
        void update_draft_staysDraft() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = new PackageRequestEntity();
            setId(e, id);
            e.setSenderId(SENDER_ID);
            e.setStatus(PackageRequestStatus.DRAFT);
            when(repository.findById(id)).thenReturn(Optional.of(e));
            when(threadRepository.findByPackageRequestId(id)).thenReturn(List.of());
            when(commissionProperties.rate()).thenReturn(new BigDecimal("0.05"));
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(i -> i.getArgument(0));

            service.update(SENDER_ID, id, draftRequest(null));

            // Sans garde, update() posait OPEN en dur et publiait le brouillon en
            // silence — la demande devenait visible de tous à la première édition.
            assertThat(e.getStatus()).isEqualTo(PackageRequestStatus.DRAFT);
        }

        @Test @DisplayName("éditer une demande en négociation la repasse OPEN")
        void update_negotiating_returnsToOpen() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = new PackageRequestEntity();
            setId(e, id);
            e.setSenderId(SENDER_ID);
            e.setStatus(PackageRequestStatus.NEGOTIATING);
            when(repository.findById(id)).thenReturn(Optional.of(e));
            when(threadRepository.findByPackageRequestId(id)).thenReturn(List.of());
            when(commissionProperties.rate()).thenReturn(new BigDecimal("0.05"));
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(i -> i.getArgument(0));

            service.update(SENDER_ID, id, draftRequest(null));

            assertThat(e.getStatus()).isEqualTo(PackageRequestStatus.OPEN);
        }
    }
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./mvnw test -Dtest=PackageRequestServiceTest`
Expected: FAIL — `update_draft_staysDraft` échoue en `409 request/not-editable` (DRAFT n'est pas dans les statuts éditables).

- [ ] **Step 3: Autoriser l'édition d'un brouillon**

Dans `PackageRequestService.update`, remplacer le contrôle de statut (lignes 215-218) :

```java
        if (entity.getStatus() != PackageRequestStatus.DRAFT
            && entity.getStatus() != PackageRequestStatus.OPEN
            && entity.getStatus() != PackageRequestStatus.NEGOTIATING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/not-editable");
        }
```

- [ ] **Step 4: Ne plus forcer OPEN sur un brouillon**

Remplacer la ligne 260 (`entity.setStatus(PackageRequestStatus.OPEN);`) par :

```java
        // Repasser en OPEN sert à sortir d'une négociation dont les termes ont
        // changé. Un brouillon n'a pas de négociation et ne doit pas être publié
        // par une simple édition.
        if (entity.getStatus() != PackageRequestStatus.DRAFT) {
            entity.setStatus(PackageRequestStatus.OPEN);
        }
```

- [ ] **Step 5: Lancer les tests pour vérifier qu'ils passent**

Run: `./mvnw test -Dtest=PackageRequestServiceTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yadony/api/requests/service/PackageRequestService.java \
        src/test/java/com/yadony/api/requests/service/PackageRequestServiceTest.java
git commit -m "fix(requests): l'édition d'un brouillon ne le publie plus"
```

---

### Task 5 : un brouillon est invisible des tiers

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/service/PackageRequestService.java:278-305`
- Test: `src/test/java/com/yadony/api/requests/service/PackageRequestServiceTest.java`

**Interfaces:**
- Consumes: `PackageRequestStatus.DRAFT` (Task 1).
- Produces: rien de nouveau — durcit `getById`.

- [ ] **Step 1: Écrire les tests qui échouent**

```java
    @Nested @DisplayName("getById() — brouillon")
    class GetByIdDraft {

        private PackageRequestEntity draftOwnedBySender(UUID id) {
            PackageRequestEntity e = new PackageRequestEntity();
            setId(e, id);
            e.setSenderId(SENDER_ID);
            e.setDepartureCity("Paris");
            e.setArrivalCity("Dakar");
            e.setDesiredDate(LocalDate.now().plusDays(10));
            e.setDateToleranceDays((short) 2);
            e.setWeightKg(new BigDecimal("3.0"));
            e.setContentCategory("Documents");
            e.setNegotiable(true);
            e.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
            e.setStatus(PackageRequestStatus.DRAFT);
            return e;
        }

        @Test @DisplayName("le propriétaire voit son brouillon")
        void getById_owner_seesDraft() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(draftOwnedBySender(id)));

            assertThatCode(() -> service.getById(SENDER_ID, id)).doesNotThrowAnyException();
        }

        @Test @DisplayName("un tiers reçoit 404, pas 403")
        void getById_stranger_throws404() {
            UUID id = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(draftOwnedBySender(id)));
            when(threadRepository.existsByPackageRequestIdAndTravelerId(id, stranger))
                    .thenReturn(false);

            // 403 révélerait qu'une demande existe derrière cet id.
            assertThatThrownBy(() -> service.getById(stranger, id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("request/not-found");
        }
    }
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./mvnw test -Dtest=PackageRequestServiceTest`
Expected: FAIL — `getById_stranger_throws404` obtient `request/forbidden`.

- [ ] **Step 3: Masquer le brouillon aux tiers**

Dans `PackageRequestService.getById`, remplacer le bloc de contrôle d'accès (lignes 291-296) :

```java
        boolean isPubliclyListed = entity.getStatus() == PackageRequestStatus.OPEN
            || entity.getStatus() == PackageRequestStatus.NEGOTIATING;

        if (!isOwner && !isThreadParticipant && !isPubliclyListed) {
            // Un brouillon n'a jamais été rendu public : répondre « interdit »
            // apprendrait à un tiers qu'une demande existe derrière cet id. Les
            // autres statuts non listés ont, eux, déjà été publics.
            HttpStatus status = entity.getStatus() == PackageRequestStatus.DRAFT
                ? HttpStatus.NOT_FOUND
                : HttpStatus.FORBIDDEN;
            String reason = entity.getStatus() == PackageRequestStatus.DRAFT
                ? "request/not-found"
                : "request/forbidden";
            throw new ResponseStatusException(status, reason);
        }
```

- [ ] **Step 4: Lancer les tests pour vérifier qu'ils passent**

Run: `./mvnw test -Dtest=PackageRequestServiceTest`
Expected: PASS.

- [ ] **Step 5: Vérifier que la recherche publique exclut déjà les brouillons**

`PackageRequestSpecifications.java:16` filtre sur `OPEN, NEGOTIATING` — `DRAFT` en est exclu par construction, aucun changement nécessaire. Confirmer par lecture :

Run: `grep -n "PackageRequestStatus" src/main/java/com/yadony/api/requests/specification/PackageRequestSpecifications.java`
Expected: la liste ne contient que `OPEN` et `NEGOTIATING`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yadony/api/requests/service/PackageRequestService.java \
        src/test/java/com/yadony/api/requests/service/PackageRequestServiceTest.java
git commit -m "feat(requests): rend un brouillon invisible des tiers"
```

---

### Task 6 : dépublier une demande

**Files:**
- Modify: `src/main/java/com/yadony/api/requests/service/PackageRequestService.java`
- Modify: `src/main/java/com/yadony/api/requests/controller/PackageRequestController.java`
- Test: `src/test/java/com/yadony/api/requests/service/PackageRequestServiceTest.java`
- Test: `src/test/java/com/yadony/api/requests/controller/PackageRequestControllerIT.java`

**Interfaces:**
- Consumes: `assertDraftQuotaAvailable` (Task 2).
- Produces: `PackageRequestService.unpublish(UUID callerUid, UUID requestId)` → `PackageRequestResponse` ; endpoint `POST /package-requests/{id}/unpublish`.

- [ ] **Step 1: Écrire les tests qui échouent**

```java
    @Nested @DisplayName("unpublish()")
    class Unpublish {

        private PackageRequestEntity openRequest(UUID id) {
            PackageRequestEntity e = new PackageRequestEntity();
            setId(e, id);
            e.setSenderId(SENDER_ID);
            e.setDepartureCity("Paris");
            e.setArrivalCity("Dakar");
            e.setDesiredDate(LocalDate.now().plusDays(10));
            e.setDateToleranceDays((short) 2);
            e.setWeightKg(new BigDecimal("3.0"));
            e.setContentCategory("Documents");
            e.setNegotiable(true);
            e.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
            e.setStatus(PackageRequestStatus.OPEN);
            return e;
        }

        @Test @DisplayName("OPEN sans offre → DRAFT + audit UNPUBLISHED")
        void unpublish_openWithoutOffers_becomesDraft() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = openRequest(id);
            when(repository.findById(id)).thenReturn(Optional.of(e));
            when(threadRepository.findByPackageRequestId(id)).thenReturn(List.of());
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatus(SENDER_ID, PackageRequestStatus.DRAFT))
                    .thenReturn(0L);
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(i -> i.getArgument(0));

            service.unpublish(SENDER_ID, id);

            assertThat(e.getStatus()).isEqualTo(PackageRequestStatus.DRAFT);
            verify(auditService).log(eq("PACKAGE_REQUEST"), eq(id), eq("UNPUBLISHED"),
                    eq(SENDER_ID), any());
        }

        @Test @DisplayName("au moins une offre → 409 request/has-offers")
        void unpublish_withOffers_throws409() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(openRequest(id)));
            when(threadRepository.findByPackageRequestId(id))
                    .thenReturn(List.of(new NegotiationThreadEntity()));

            assertThatThrownBy(() -> service.unpublish(SENDER_ID, id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("request/has-offers");
        }

        @Test @DisplayName("statut non OPEN → 409 request/not-unpublishable")
        void unpublish_notOpen_throws409() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = openRequest(id);
            e.setStatus(PackageRequestStatus.ACCEPTED);
            when(repository.findById(id)).thenReturn(Optional.of(e));

            assertThatThrownBy(() -> service.unpublish(SENDER_ID, id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("request/not-unpublishable");
        }

        @Test @DisplayName("non-propriétaire → 403 request/forbidden")
        void unpublish_notOwner_throws403() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(openRequest(id)));

            assertThatThrownBy(() -> service.unpublish(UUID.randomUUID(), id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("request/forbidden");
        }

        @Test @DisplayName("quota de brouillons atteint → 403 draft-limit-reached")
        void unpublish_overDraftQuota_throws403() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(openRequest(id)));
            when(threadRepository.findByPackageRequestId(id)).thenReturn(List.of());
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatus(SENDER_ID, PackageRequestStatus.DRAFT))
                    .thenReturn(1L);

            // Sans ce contrôle, dépublier serait un moyen de dépasser le plafond.
            assertThatThrownBy(() -> service.unpublish(SENDER_ID, id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("draft-limit-reached");
        }
    }
```

Ajouter l'import de `NegotiationThreadEntity` en tête du fichier de test s'il n'y est pas :

```java
import com.yadony.api.requests.entity.NegotiationThreadEntity;
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./mvnw test -Dtest=PackageRequestServiceTest`
Expected: FAIL — `unpublish` n'existe pas.

- [ ] **Step 3: Implémenter `unpublish`**

Ajouter dans `PackageRequestService`, après `publish` :

```java
    // ─── unpublish ───────────────────────────────────────────────────────────────

    /**
     * Retire une demande de la circulation sans l'annuler (OPEN → DRAFT).
     *
     * <p>Annuler est terminal ; dépublier ne l'est pas. L'opération n'est ouverte
     * que tant qu'aucun voyageur ne s'est engagé : au-delà, des tiers ont agi sur
     * la foi de la publication et le retrait unilatéral ne leur est pas opposable.
     */
    @Transactional
    public PackageRequestResponse unpublish(UUID callerUid, UUID requestId) {
        PackageRequestEntity entity = repository.findById(requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        // La demande est publique ici : 403 ne révèle rien qu'on ne sache déjà.
        if (!entity.getSenderId().equals(callerUid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "request/forbidden");
        }
        if (entity.getStatus() != PackageRequestStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/not-unpublishable");
        }
        // Test distinct du précédent : un thread peut exister alors que la demande
        // est encore OPEN (offre reçue mais pas encore ouverte en négociation).
        if (!threadRepository.findByPackageRequestId(requestId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/has-offers");
        }

        UserEntity sender = userRepository.findById(callerUid)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        assertDraftQuotaAvailable(callerUid, sender);

        entity.setStatus(PackageRequestStatus.DRAFT);
        PackageRequestEntity saved = repository.save(entity);

        auditService.log("PACKAGE_REQUEST", saved.getId(), "UNPUBLISHED", callerUid,
            Map.of("corridor", saved.getDepartureCity() + "->" + saved.getArrivalCity()));

        return toResponse(saved);
    }
```

- [ ] **Step 4: Lancer les tests de service pour vérifier qu'ils passent**

Run: `./mvnw test -Dtest=PackageRequestServiceTest`
Expected: PASS.

- [ ] **Step 5: Exposer l'endpoint**

Ajouter dans `PackageRequestController`, après l'endpoint `publish` :

```java
    /** Retire une demande de la circulation sans l'annuler (OPEN → DRAFT). */
    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasRole('SENDER')")
    public PackageRequestResponse unpublish(@PathVariable UUID id) {
        return service.unpublish(requireUserId(), id);
    }
```

- [ ] **Step 6: Écrire le test d'intégration**

Ajouter dans `PackageRequestControllerIT` :

```java
    @Test
    @DisplayName("POST /package-requests/{id}/unpublish — demande ouverte sans offre → 200 + DRAFT")
    void unpublish_openWithoutOffers_returns200() throws Exception {
        UUID id = createOpenRequestForCurrentSender();

        mockMvc.perform(post("/package-requests/" + id + "/unpublish")
                        .header("Authorization", "Bearer " + senderToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @DisplayName("POST /package-requests/{id}/unpublish — demande d'autrui → 403")
    void unpublish_othersRequest_returns403() throws Exception {
        UUID id = createOpenRequestForOtherSender();

        mockMvc.perform(post("/package-requests/" + id + "/unpublish")
                        .header("Authorization", "Bearer " + senderToken()))
                .andExpect(status().isForbidden());
    }
```

- [ ] **Step 7: Lancer les tests d'intégration**

Run: `./mvnw test -Dtest=PackageRequestControllerIT`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/yadony/api/requests/ src/test/java/com/yadony/api/requests/
git commit -m "feat(requests): permet de dépublier une demande sans offre"
```

---

### Task 7 : dépublier un trajet

**Files:**
- Modify: `src/main/java/com/yadony/api/matching/AnnouncementService.java`
- Modify: `src/main/java/com/yadony/api/matching/AnnouncementController.java`
- Test: `src/test/java/com/yadony/api/matching/AnnouncementServiceTest.java`

**Interfaces:**
- Produces: `AnnouncementService.unpublishAnnouncement(UUID id, String firebaseUid)` → `AnnouncementDetailResponse` ; endpoint `POST /announcements/{id}/unpublish`.

- [ ] **Step 1: Écrire les tests qui échouent**

Ajouter dans `AnnouncementServiceTest`, en reprenant les helpers de construction d'annonce et d'utilisateur déjà présents dans ce fichier :

```java
    @Nested
    @DisplayName("unpublishAnnouncement()")
    class UnpublishAnnouncement {

        @Test
        @DisplayName("ACTIVE sans demande → DRAFT + audit UNPUBLISHED")
        void unpublish_activeWithoutBids_becomesDraft() {
            // Construire une annonce ACTIVE appartenant à l'utilisateur courant,
            // avec zéro bid, puis appeler unpublishAnnouncement et vérifier :
            //   - statut passé à AnnouncementStatus.DRAFT
            //   - auditService.log(..., "UNPUBLISHED", ...) appelé
        }

        @Test
        @DisplayName("au moins une demande reçue → 409 announcement/has-bids")
        void unpublish_withBids_throws409() {
            // Annonce ACTIVE avec au moins un bid → YadonyBusinessException
            // portant le code announcement/has-bids.
        }

        @Test
        @DisplayName("statut non ACTIVE → 409 announcement/not-unpublishable")
        void unpublish_notActive_throws409() {
            // Annonce COMPLETED → announcement/not-unpublishable.
        }

        @Test
        @DisplayName("non-propriétaire → 403")
        void unpublish_notOwner_throws403() {
            // Autre voyageur → 403.
        }

        @Test
        @DisplayName("quota de brouillons atteint → 403 draft-limit-reached")
        void unpublish_overDraftQuota_throws403() {
            // countByTravelerIdAndStatus(..., DRAFT) au plafond → draft-limit-reached.
        }
    }
```

Remplir chaque corps en suivant le style des tests voisins du fichier (mêmes mocks, mêmes fabriques d'entités). Les commentaires décrivent l'assertion attendue, pas le code final.

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./mvnw test -Dtest=AnnouncementServiceTest`
Expected: FAIL — `unpublishAnnouncement` n'existe pas.

- [ ] **Step 3: Implémenter `unpublishAnnouncement`**

Ajouter dans `AnnouncementService`, juste après `publishAnnouncement` (ligne 835) :

```java
    /**
     * Retire un trajet de la circulation sans l'annuler (ACTIVE → DRAFT).
     *
     * <p>Symétrique de {@link #publishAnnouncement}. Refusé dès qu'un expéditeur
     * a envoyé une demande : il a agi sur la foi de la publication.
     */
    @Transactional
    public AnnouncementDetailResponse unpublishAnnouncement(UUID id, String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));

        AnnouncementEntity announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        if (!announcement.getTravelerId().equals(user.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "announcement-forbidden",
                    "Forbidden", "Ce trajet ne vous appartient pas");
        }
        if (announcement.getStatus() != AnnouncementStatus.ACTIVE) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "announcement/not-unpublishable",
                    "Not Unpublishable", "Seul un trajet actif peut être dépublié");
        }
        long bidCount = bidRepository.countByAnnouncementId(id);
        if (bidCount > 0) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "announcement/has-bids",
                    "Has Bids", "Ce trajet a déjà reçu des demandes et ne peut plus être dépublié");
        }

        // Même plafond que la création d'un brouillon : sans ce contrôle,
        // dépublier permettrait de dépasser la limite.
        YadonyConfigProperties.Limits limits = config.limits() != null
                ? config.limits()
                : new YadonyConfigProperties.Limits(null, null);
        int maxDrafts = user.isProAccount() ? limits.maxDraftsPro() : limits.maxDrafts();
        long draftCount = announcementRepository
                .countByTravelerIdAndStatus(user.getId(), AnnouncementStatus.DRAFT);
        if (draftCount >= maxDrafts) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "draft-limit-reached",
                    "Draft Limit Reached",
                    "Limite de " + maxDrafts + " brouillon(s) atteinte.");
        }

        announcement.setStatus(AnnouncementStatus.DRAFT);
        AnnouncementEntity saved = announcementRepository.save(announcement);

        auditService.log("ANNOUNCEMENT", user.getId(), "ANNOUNCEMENT_UNPUBLISHED", id,
                Map.of("corridor", saved.getDepartureCity() + "->" + saved.getArrivalCity()));

        return toDetailResponse(saved, user);
    }
```

Vérifier les noms exacts avant d'écrire : la signature de `auditService.log` et celle de `toDetailResponse` doivent être copiées sur `publishAnnouncement` du même fichier, dont l'ordre des arguments fait foi. Si `bidRepository.countByAnnouncementId` n'existe pas, l'ajouter à `BidRepository` :

```java
    long countByAnnouncementId(UUID announcementId);
```

- [ ] **Step 4: Lancer les tests de service pour vérifier qu'ils passent**

Run: `./mvnw test -Dtest=AnnouncementServiceTest`
Expected: PASS.

- [ ] **Step 5: Exposer l'endpoint**

Ajouter dans `AnnouncementController`, après `publishAnnouncement` (ligne 123) :

```java
    @PostMapping("/{id}/unpublish")
    public ResponseEntity<AnnouncementDetailResponse> unpublishAnnouncement(@PathVariable UUID id) {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(announcementService.unpublishAnnouncement(id, firebaseUid));
    }
```

- [ ] **Step 6: Lancer toute la suite**

Run: `./mvnw test`
Expected: PASS, 0 rouge.

- [ ] **Step 7: Vérifier la couverture**

Run: `./mvnw test jacoco:report`
Ouvrir `target/site/jacoco/index.html` — couverture globale ≥ 90 %. Si les nouvelles méthodes sont sous-couvertes, ajouter les tests manquants avant de commiter.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/yadony/api/matching/ src/test/java/com/yadony/api/matching/
git commit -m "feat(matching): permet de dépublier un trajet sans demande"
```

---

## Vérification finale

- [ ] `./mvnw test` — 0 rouge
- [ ] `./mvnw test jacoco:report` — couverture ≥ 90 %
- [ ] `git log --oneline` — 7 commits fonctionnels + le commit de spec, aucun sur `main`
- [ ] Ouvrir la PR sur `dony-back` en référençant la spec

Le plan Flutter (`dony_app`) dépend des quatre endpoints livrés ici : `POST /package-requests/{id}/publish`, `POST /package-requests/{id}/unpublish`, `POST /announcements/{id}/unpublish`, et le champ `saveAsDraft` de `POST /package-requests`.
