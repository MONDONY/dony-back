# Vocabulaire unifié des types de contenu — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remplacer les 9 listes de types de contenu divergentes des 3 projets par un catalogue unique servi par le backend, avec liste déroulante + saisie libre partout.

**Architecture:** Le backend porte le catalogue canonique (11 catégories, constante Java — pas de config YAML) et le sert via `GET /config/content-categories` sous forme `{code, label, emoji}`. La **valeur persistée reste le libellé** (jamais le code), ce qui laisse `BidContentRules` et le moteur d'automatisation inchangés. Une migration Flyway V171 normalise les données existantes (codes → libellés pour les demandes de colis, anciens libellés → nouveaux partout ailleurs). Flutter et dony-pro consomment l'endpoint et suppriment leurs listes en dur.

**Tech Stack:** Spring Boot 3.4 / Java 21 / Flyway / PostgreSQL ; Flutter (BLoC, Dio) ; Nuxt 4 / Vue 3.5 / TypeScript.

**Spec:** `dony-back/docs/superpowers/specs/2026-07-12-vocabulaire-contenus-unifie-design.md` — la spec fait foi.

**Branche dony-back:** `feature/vocabulaire-contenus-unifie` (déjà créée, basée sur `main`, spec commitée en `0bedce6`).
**Branches à créer :** `feature/vocabulaire-contenus-unifie` dans `dony-pro` et dans `dony_app`.

## Global Constraints

Le catalogue canonique — **valeurs exactes, à recopier verbatim, jamais à réinventer** :

| # | code | label (valeur stockée en base) | emoji |
|---|---|---|---|
| 1 | `DOCUMENTS` | `Documents & administratif` | 📄 |
| 2 | `ALIMENTATION_SECHE` | `Alimentation sèche` | 🍚 |
| 3 | `PRODUITS_FRAIS` | `Produits frais / périssables` | 🐟 |
| 4 | `COSMETIQUES` | `Cosmétiques & parfums` | 💄 |
| 5 | `VETEMENTS` | `Vêtements & tissus` | 👗 |
| 6 | `CHAUSSURES` | `Chaussures` | 👟 |
| 7 | `MEDICAMENTS_TRADITIONNELS` | `Médicaments traditionnels` | 🌿 |
| 8 | `ELECTRONIQUE` | `Téléphone & électronique` | 📱 |
| 9 | `LIVRES` | `Livres` | 📚 |
| 10 | `CADEAUX` | `Cadeaux & jouets` | 🎁 |
| 11 | `AUTRE` | `Autre` | 📦 |

- **INVARIANT CRITIQUE : aucun libellé ne contient de virgule.** `bids.content_category` encode plusieurs catégories jointes par virgule (`"Vêtements & tissus, Livres"`) ; un libellé virgulé casserait le `split(",")` de `BidContentRules` et de `CustomRuleConditionEvaluator`. Un test backend verrouille cet invariant.
- **La valeur persistée est TOUJOURS le `label`, jamais le `code`.** Le `code` est une clé technique (icônes Flutter, i18n future) et ne doit apparaître dans aucune requête d'écriture ni aucun payload d'entrée.
- **Ne modifier ni `BidContentRules.java` ni `CustomRuleConditionEvaluator.java`** — ils matchent déjà sur les libellés, insensibles à la casse et aux espaces, et continuent de fonctionner tels quels. Toute modification de ces fichiers est hors périmètre.
- **La saisie libre reste possible partout** : un type absent du catalogue (« Poissons », « Liquides ») doit pouvoir être ajouté par l'utilisateur et est stocké tel quel, aux côtés des libellés canoniques.
- Ne jamais modifier une migration existante — créer V(n+1). Dernière migration actuelle : **V170**.
- Pas de `Co-Authored-By: Claude` dans les commits. Messages en français.
- Le profil de test backend (`test`) tourne sur **H2 avec Flyway désactivé** — les migrations ne s'y exécutent pas. Le test de la migration V171 utilise PostgreSQL embarqué (zonky `EmbeddedPostgres`), sur le modèle de `src/test/java/com/dony/api/e2e/config/CucumberSpringContext.java:30-47`.

---

## Structure des fichiers

**dony-back**
- Créer `src/main/java/com/dony/api/config/ContentCatalog.java` — le catalogue canonique (constante).
- Créer `src/main/java/com/dony/api/config/dto/ContentCategoryResponse.java` — DTO `{code, label, emoji}`.
- Modifier `src/main/java/com/dony/api/config/ConfigController.java:26-30` — nouvelle forme de réponse.
- Modifier `src/main/java/com/dony/api/config/DonyConfigProperties.java:17` — retirer `contentCategories`.
- Modifier `src/main/resources/application.yml:122-131` — retirer le bloc `content-categories`.
- Créer `src/main/resources/db/migration/V171__unify_content_categories.sql`.
- Créer `src/test/java/com/dony/api/config/ContentCatalogTest.java`, `src/test/java/com/dony/api/migrations/V171ContentCategoriesMigrationTest.java`.
- Modifier `src/test/java/com/dony/api/config/ConfigControllerIT.java`.

**dony-pro**
- Modifier `app/features/trajets/services/configService.ts`, `app/features/trajets/components/ContentTagChips.vue` (ou son appelant), `app/features/trajets/components/NewAnnouncementForm.vue`, `app/features/trajets/data/tripTemplates.ts:25`.
- Modifier `app/features/automations/components/AutomationRuleModal.vue:220-226` — liste déroulante pour `content_type`.

**dony_app**
- Créer `lib/features/content_categories/` — `data/content_category_model.dart`, `data/content_category_datasource.dart`, `data/content_category_repository.dart` (cache + fallback embarqué), `presentation/content_category_selector.dart` (widget de sélection multiple réutilisable).
- Supprimer les listes en dur : `create_bid_bottom_sheet.dart:34`, `create_bid_screen.dart:25`, `create_announcement/_create_announcement_constants.dart:8`, `search_form_bottom_sheet.dart:18`, `trip_template_edit_screen.dart:21`, `corridor_alert_form_sheet.dart:17`, et l'enum `package_request/data/models/content_category.dart`.

---

### Task 1: Catalogue canonique backend + endpoint

**Files:**
- Create: `dony-back/src/main/java/com/dony/api/config/ContentCatalog.java`
- Create: `dony-back/src/main/java/com/dony/api/config/dto/ContentCategoryResponse.java`
- Modify: `dony-back/src/main/java/com/dony/api/config/ConfigController.java:26-30`
- Modify: `dony-back/src/main/java/com/dony/api/config/DonyConfigProperties.java:17`
- Modify: `dony-back/src/main/resources/application.yml:122-131`
- Test: `dony-back/src/test/java/com/dony/api/config/ContentCatalogTest.java` (créer), `dony-back/src/test/java/com/dony/api/config/ConfigControllerIT.java` (modifier)

**Interfaces:**
- Produit (consommé par Task 2 et par les fronts) :
  - `ContentCategoryResponse(String code, String label, String emoji)` — record public.
  - `ContentCatalog.CATEGORIES` : `List<ContentCategoryResponse>` public statique immuable, 11 entrées, dans l'ordre du tableau des Global Constraints.
  - `GET /config/content-categories` → `200 OK`, corps `[{"code":"DOCUMENTS","label":"Documents & administratif","emoji":"📄"}, …]`.
- **Pourquoi une constante Java et non `application.yml`** : le catalogue est un vocabulaire produit, pas un réglage d'exploitation. La version YAML actuelle a précisément dérivé de toutes les listes front. Une constante est impossible à mal configurer par environnement et se teste.

- [ ] **Step 1: Écrire les tests qui échouent**

Créer `dony-back/src/test/java/com/dony/api/config/ContentCatalogTest.java` :

```java
package com.dony.api.config;

import com.dony.api.config.dto.ContentCategoryResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ContentCatalogTest {

    @Test
    void catalog_hasElevenCategories() {
        assertThat(ContentCatalog.CATEGORIES).hasSize(11);
    }

    @Test
    void noLabelContainsComma_becauseBidContentCategoryIsCommaJoined() {
        // Invariant critique : bids.content_category encode plusieurs catégories jointes
        // par virgule. Un libellé virgulé casserait le split(",") de BidContentRules et
        // de CustomRuleConditionEvaluator.
        for (ContentCategoryResponse c : ContentCatalog.CATEGORIES) {
            assertThat(c.label()).doesNotContain(",");
        }
    }

    @Test
    void codesAreUnique() {
        Set<String> codes = ContentCatalog.CATEGORIES.stream()
                .map(ContentCategoryResponse::code)
                .collect(Collectors.toSet());
        assertThat(codes).hasSize(ContentCatalog.CATEGORIES.size());
    }

    @Test
    void labelsAreUnique() {
        Set<String> labels = ContentCatalog.CATEGORIES.stream()
                .map(ContentCategoryResponse::label)
                .collect(Collectors.toSet());
        assertThat(labels).hasSize(ContentCatalog.CATEGORIES.size());
    }

    @Test
    void everyCategoryHasCodeLabelAndEmoji() {
        for (ContentCategoryResponse c : ContentCatalog.CATEGORIES) {
            assertThat(c.code()).isNotBlank();
            assertThat(c.label()).isNotBlank();
            assertThat(c.emoji()).isNotBlank();
        }
    }

    @Test
    void catalogContainsTheExpectedLabels() {
        List<String> labels = ContentCatalog.CATEGORIES.stream()
                .map(ContentCategoryResponse::label)
                .toList();
        assertThat(labels).containsExactly(
                "Documents & administratif",
                "Alimentation sèche",
                "Produits frais / périssables",
                "Cosmétiques & parfums",
                "Vêtements & tissus",
                "Chaussures",
                "Médicaments traditionnels",
                "Téléphone & électronique",
                "Livres",
                "Cadeaux & jouets",
                "Autre");
    }

    @Test
    void catalogIsImmutable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> ContentCatalog.CATEGORIES.add(
                        new ContentCategoryResponse("X", "X", "X")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
```

Ajouter dans `dony-back/src/test/java/com/dony/api/config/ConfigControllerIT.java` (garder les tests existants sur `/config/commission-rate`, suivre leur style — MockMvc ou WebTestClient selon ce que le fichier utilise déjà, **le fichier réel fait foi**) :

```java
    @Test
    void getContentCategories_returnsCatalogWithCodeLabelAndEmoji() throws Exception {
        mockMvc.perform(get("/config/content-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(11))
                .andExpect(jsonPath("$[0].code").value("DOCUMENTS"))
                .andExpect(jsonPath("$[0].label").value("Documents & administratif"))
                .andExpect(jsonPath("$[0].emoji").value("📄"))
                .andExpect(jsonPath("$[2].label").value("Produits frais / périssables"));
    }
```

- [ ] **Step 2: Lancer les tests, vérifier l'échec**

Run: `cd dony-back && ./mvnw test -Dtest='ContentCatalogTest+ConfigControllerIT'`
Expected: FAIL — erreur de compilation, `ContentCatalog` et `ContentCategoryResponse` n'existent pas.

- [ ] **Step 3: Implémenter**

Créer `dony-back/src/main/java/com/dony/api/config/dto/ContentCategoryResponse.java` :

```java
package com.dony.api.config.dto;

/**
 * Une catégorie du catalogue canonique des types de contenu.
 *
 * @param code  clé technique stable (lookup d'icône côté client, i18n future).
 *              JAMAIS persistée en base.
 * @param label libellé d'affichage — c'est LA valeur stockée
 *              ({@code bids.content_category}, {@code announcement_accepted_types}, etc.).
 * @param emoji pictogramme d'affichage.
 */
public record ContentCategoryResponse(String code, String label, String emoji) {}
```

Créer `dony-back/src/main/java/com/dony/api/config/ContentCatalog.java` :

```java
package com.dony.api.config;

import com.dony.api.config.dto.ContentCategoryResponse;

import java.util.List;

/**
 * Catalogue canonique des types de contenu d'un colis — source de vérité unique,
 * servie aux clients par {@code GET /config/content-categories}.
 *
 * <p>Dérivé de l'analyse de 2 610 annonces du corridor Paris-Abidjan (361 mentionnant
 * explicitement un contenu). Volontairement corridor-agnostique.
 *
 * <p><b>C'est une constante, pas une configuration.</b> Le catalogue est un vocabulaire
 * produit, pas un réglage d'exploitation : le sortir en YAML l'exposerait à diverger par
 * environnement — exactement ce qui a produit les 9 listes divergentes que ce chantier
 * supprime.
 *
 * <p><b>INVARIANT : aucun libellé ne contient de virgule.</b> {@code bids.content_category}
 * encode plusieurs catégories jointes par virgule ; un libellé virgulé casserait le
 * {@code split(",")} de {@code BidContentRules} et de {@code CustomRuleConditionEvaluator}.
 * Verrouillé par {@code ContentCatalogTest}.
 */
public final class ContentCatalog {

    public static final List<ContentCategoryResponse> CATEGORIES = List.of(
            new ContentCategoryResponse("DOCUMENTS", "Documents & administratif", "📄"),
            new ContentCategoryResponse("ALIMENTATION_SECHE", "Alimentation sèche", "🍚"),
            new ContentCategoryResponse("PRODUITS_FRAIS", "Produits frais / périssables", "🐟"),
            new ContentCategoryResponse("COSMETIQUES", "Cosmétiques & parfums", "💄"),
            new ContentCategoryResponse("VETEMENTS", "Vêtements & tissus", "👗"),
            new ContentCategoryResponse("CHAUSSURES", "Chaussures", "👟"),
            new ContentCategoryResponse("MEDICAMENTS_TRADITIONNELS", "Médicaments traditionnels", "🌿"),
            new ContentCategoryResponse("ELECTRONIQUE", "Téléphone & électronique", "📱"),
            new ContentCategoryResponse("LIVRES", "Livres", "📚"),
            new ContentCategoryResponse("CADEAUX", "Cadeaux & jouets", "🎁"),
            new ContentCategoryResponse("AUTRE", "Autre", "📦"));

    private ContentCatalog() {}
}
```

Modifier `ConfigController.java` — remplacer la méthode `getContentCategories` :

```java
    @GetMapping("/content-categories")
    public ResponseEntity<List<ContentCategoryResponse>> getContentCategories() {
        return ResponseEntity.ok(ContentCatalog.CATEGORIES);
    }
```

Ajouter l'import `com.dony.api.config.dto.ContentCategoryResponse` ; le champ `config` reste utilisé par `getCommissionRate`, ne pas le retirer.

Modifier `DonyConfigProperties.java` — retirer `List<String> contentCategories` du record (et l'import `java.util.List` s'il devient inutilisé).

Modifier `application.yml` — supprimer le bloc `content-categories:` (lignes 122-131) en entier.

- [ ] **Step 4: Vérifier que les tests passent**

Run: `cd dony-back && ./mvnw test -Dtest='ContentCatalogTest+ConfigControllerIT'`
Expected: PASS.

- [ ] **Step 5: Suite complète (attention aux consommateurs de `contentCategories()`)**

Run: `cd dony-back && ./mvnw test`
Expected: BUILD SUCCESS. Si un test référence `config.contentCategories()`, le corriger (le seul appelant connu était `ConfigController`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/dony/api/config/ src/main/resources/application.yml src/test/java/com/dony/api/config/
git commit -m "feat(contenus): catalogue canonique des types de contenu + endpoint {code,label,emoji}"
```

---

### Task 2: Migration V171 — normalisation des données existantes

**Files:**
- Create: `dony-back/src/main/resources/db/migration/V171__unify_content_categories.sql`
- Test: `dony-back/src/test/java/com/dony/api/migrations/V171ContentCategoriesMigrationTest.java`

**Interfaces:**
- Consomme (Task 1) : les 11 libellés canoniques (valeurs verbatim des Global Constraints).
- Produit : données de production normalisées sur le nouveau vocabulaire.

**Contexte indispensable.** Trois formes coexistent aujourd'hui en base :

| Table.colonne | Forme actuelle | Exemple |
|---|---|---|
| `package_requests.content_category` | **code majuscule** (enum Flutter `wireName`) | `VETEMENTS`, `ALIMENTATION`, `HIFI` |
| `bids.content_category` | **libellés joints par virgule** | `Vêtements, Poissons` |
| `announcement_accepted_types.content_type` | **libellé** (une ligne par item) | `Hi-fi` |
| `announcement_refused_types.content_type` | **libellé / texte libre** | `Liquides` |

Sans migration, un voyageur ayant accepté `Hi-fi` ne matcherait plus un colis `Téléphone & électronique`.

**Table de correspondance — legacy → canonique** (à appliquer partout) :

| Valeur legacy | Nouveau libellé |
|---|---|
| `VETEMENTS` (code) | `Vêtements & tissus` |
| `MEDICAMENTS` (code) | `Médicaments traditionnels` |
| `ALIMENTATION` (code) | `Alimentation sèche` |
| `HIFI` (code) | `Téléphone & électronique` |
| `DOCUMENTS` (code) | `Documents & administratif` |
| `TELEPHONE` (code) | `Téléphone & électronique` |
| `COSMETIQUES` (code) | `Cosmétiques & parfums` |
| `CADEAUX` (code) | `Cadeaux & jouets` |
| `AUTRE` (code) | `Autre` |
| `Téléphones & hi-fi` | `Téléphone & électronique` |
| `Matériel informatique` | `Téléphone & électronique` |
| `Alim. sèche` | `Alimentation sèche` |
| `Alimentation sèche` | *(inchangé)* |
| `Électronique` | `Téléphone & électronique` |
| `Nourriture` | `Alimentation sèche` |
| `Hi-fi` | `Téléphone & électronique` |
| `Téléphone` | `Téléphone & électronique` |
| `Cosmétiques` | `Cosmétiques & parfums` |
| `Cosmét.` | `Cosmétiques & parfums` |
| `Vêtements` | `Vêtements & tissus` |
| `Médicaments` | `Médicaments traditionnels` |
| `Documents` | `Documents & administratif` |
| `Cadeaux` | `Cadeaux & jouets` |
| `Chaussures` | *(inchangé)* |
| `Autres` | `Autre` |
| `Autre` | *(inchangé)* |
| toute autre valeur (`Poissons`, `Liquides`, …) | **laissée intacte** |

**⚠️ Piège d'ordonnancement sur `bids.content_category`.** Cette colonne est une chaîne unique ; on y applique des `REPLACE` successifs. Un remplacement naïf corrompt les données : remplacer `Téléphone` → `Téléphone & électronique` AVANT `Téléphones & hi-fi` transformerait `Téléphones & hi-fi` en `Téléphone & électroniques & hi-fi`. **Traiter impérativement du libellé le plus long au plus court.** De même, `Alimentation sèche` étant déjà canonique, ne pas le re-remplacer.

- [ ] **Step 1: Écrire le test qui échoue**

Créer `dony-back/src/test/java/com/dony/api/migrations/V171ContentCategoriesMigrationTest.java`.

Ce test n'utilise **pas** le profil `test` (H2, Flyway désactivé) : il démarre un PostgreSQL embarqué, migre jusqu'à V170, insère des données legacy, applique V171, puis vérifie. Il ne dépend d'aucun contexte Spring.

```java
package com.dony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V171 — normalisation du vocabulaire des types de contenu.
 *
 * <p>Le profil "test" tourne sur H2 avec Flyway désactivé : les migrations n'y sont jamais
 * exécutées. On démarre donc un PostgreSQL embarqué (zonky, même dépendance que le harnais
 * e2e), on migre jusqu'à V170, on sème des données legacy, puis on applique V171 et on
 * vérifie le résultat.
 */
class V171ContentCategoriesMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    private Flyway flywayUpTo(String targetVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .target(targetVersion)
                .cleanDisabled(false)
                .load();
    }

    private List<String> queryStrings(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private void exec(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void v171_normalisesLegacyValues_preservesFreeText_andIsIdempotent() throws Exception {
        Flyway upToV170 = flywayUpTo("170");
        upToV170.clean();
        upToV170.migrate();

        // --- Données legacy ---
        // announcement_accepted_types / refused_types : libellés, clé = announcement_id.
        // On insère directement dans les tables de collection : l'annonce parente doit
        // exister si une FK la contraint — l'implémenteur adaptera en créant d'abord une
        // annonce minimale (et un user si nécessaire) selon le schéma réel. Le fichier
        // de migration réel fait foi sur les colonnes NOT NULL.
        String annId = seedMinimalAnnouncement();
        exec("INSERT INTO announcement_accepted_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Hi-fi'), "
                + "('" + annId + "', 'Téléphone'), "
                + "('" + annId + "', 'Alim. sèche'), "
                + "('" + annId + "', 'Poissons')");
        exec("INSERT INTO announcement_refused_types (announcement_id, content_type) VALUES "
                + "('" + annId + "', 'Médicaments'), "
                + "('" + annId + "', 'Liquides')");

        String bidId = seedMinimalBid(annId, "Vêtements, Hi-fi, Poissons");
        String reqId = seedMinimalPackageRequest("VETEMENTS");

        // --- Migration ---
        flywayUpTo("171").migrate();

        // Libellés canoniques, doublons dédupliqués (Hi-fi et Téléphone convergent).
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "'"))
                .containsExactlyInAnyOrder(
                        "Téléphone & électronique", "Alimentation sèche", "Poissons");

        assertThat(queryStrings(
                "SELECT content_type FROM announcement_refused_types WHERE announcement_id='" + annId + "'"))
                .containsExactlyInAnyOrder("Médicaments traditionnels", "Liquides");

        // Chaîne jointe par virgule : chaque item remplacé, texte libre préservé.
        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Vêtements & tissus, Téléphone & électronique, Poissons");

        // Code enum → libellé.
        assertThat(queryStrings("SELECT content_category FROM package_requests WHERE id='" + reqId + "'"))
                .containsExactly("Vêtements & tissus");

        // --- Idempotence : rejouer le corps de V171 ne doit rien changer. ---
        exec(readMigrationBody());
        assertThat(queryStrings("SELECT content_category FROM bids WHERE id='" + bidId + "'"))
                .containsExactly("Vêtements & tissus, Téléphone & électronique, Poissons");
        assertThat(queryStrings(
                "SELECT content_type FROM announcement_accepted_types WHERE announcement_id='" + annId + "'"))
                .containsExactlyInAnyOrder(
                        "Téléphone & électronique", "Alimentation sèche", "Poissons");
    }

    /** Lit le corps de V171 pour tester son idempotence en le rejouant. */
    private String readMigrationBody() throws Exception {
        try (var in = getClass().getResourceAsStream("/db/migration/V171__unify_content_categories.sql")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // Les trois helpers ci-dessous insèrent le minimum de lignes requis par les contraintes
    // NOT NULL / FK réelles du schéma. L'implémenteur les écrit en lisant les migrations
    // de création (V1 users, V3 announcements/bids, V57 package_requests) — le schéma réel
    // fait foi. Chacun retourne l'UUID de la ligne créée.
    private String seedMinimalAnnouncement() throws Exception { /* à implémenter */ return null; }
    private String seedMinimalBid(String announcementId, String contentCategory) throws Exception { /* à implémenter */ return null; }
    private String seedMinimalPackageRequest(String contentCategory) throws Exception { /* à implémenter */ return null; }
}
```

**Note à l'implémenteur :** les trois helpers de seed doivent être écrits en lisant le schéma réel (colonnes `NOT NULL`, clés étrangères) dans les migrations de création. Ne pas deviner — ouvrir `V1__*.sql` (users), `V3__*.sql` (announcements/bids) et `V57__package_requests.sql`. Si une FK impose un `user`, le créer aussi.

- [ ] **Step 2: Lancer le test, vérifier l'échec**

Run: `cd dony-back && ./mvnw test -Dtest=V171ContentCategoriesMigrationTest`
Expected: FAIL — `V171__unify_content_categories.sql` n'existe pas (Flyway `target("171")` ne trouve rien à appliquer, les assertions tombent sur les valeurs legacy).

- [ ] **Step 3: Écrire la migration**

Créer `dony-back/src/main/resources/db/migration/V171__unify_content_categories.sql` :

```sql
-- V171 — Vocabulaire unifié des types de contenu.
--
-- Trois formes coexistaient :
--   package_requests.content_category      : code enum majuscule ('VETEMENTS')
--   bids.content_category                  : libellés joints par virgule ('Vêtements, Poissons')
--   announcement_{accepted,refused}_types  : libellés, une ligne par item
--
-- On converge sur le LIBELLÉ canonique partout. Les valeurs libres non reconnues
-- ('Poissons', 'Liquides') sont laissées intactes — c'est voulu.
--
-- Idempotente : rejouable sans dommage (les libellés canoniques ne sont jamais
-- la source d'un remplacement, sauf 'Téléphone & électronique' qui n'apparaît
-- dans aucune valeur legacy).

-- ─── 1. package_requests : code enum → libellé ───────────────────────────────
UPDATE package_requests SET content_category = CASE content_category
    WHEN 'VETEMENTS'   THEN 'Vêtements & tissus'
    WHEN 'MEDICAMENTS' THEN 'Médicaments traditionnels'
    WHEN 'ALIMENTATION' THEN 'Alimentation sèche'
    WHEN 'HIFI'        THEN 'Téléphone & électronique'
    WHEN 'DOCUMENTS'   THEN 'Documents & administratif'
    WHEN 'TELEPHONE'   THEN 'Téléphone & électronique'
    WHEN 'COSMETIQUES' THEN 'Cosmétiques & parfums'
    WHEN 'CADEAUX'     THEN 'Cadeaux & jouets'
    WHEN 'AUTRE'       THEN 'Autre'
    ELSE content_category
END
WHERE content_category IN ('VETEMENTS','MEDICAMENTS','ALIMENTATION','HIFI','DOCUMENTS',
                           'TELEPHONE','COSMETIQUES','CADEAUX','AUTRE');

-- ─── 2. announcement_accepted_types / refused_types : libellé → libellé ──────
-- Une ligne par item : un CASE d'égalité exacte suffit, pas de REPLACE.
UPDATE announcement_accepted_types SET content_type = CASE content_type
    WHEN 'Téléphones & hi-fi'    THEN 'Téléphone & électronique'
    WHEN 'Matériel informatique' THEN 'Téléphone & électronique'
    WHEN 'Électronique'          THEN 'Téléphone & électronique'
    WHEN 'Hi-fi'                 THEN 'Téléphone & électronique'
    WHEN 'Téléphone'             THEN 'Téléphone & électronique'
    WHEN 'Alim. sèche'           THEN 'Alimentation sèche'
    WHEN 'Nourriture'            THEN 'Alimentation sèche'
    WHEN 'Cosmétiques'           THEN 'Cosmétiques & parfums'
    WHEN 'Cosmét.'               THEN 'Cosmétiques & parfums'
    WHEN 'Vêtements'             THEN 'Vêtements & tissus'
    WHEN 'Médicaments'           THEN 'Médicaments traditionnels'
    WHEN 'Documents'             THEN 'Documents & administratif'
    WHEN 'Cadeaux'               THEN 'Cadeaux & jouets'
    WHEN 'Autres'                THEN 'Autre'
    ELSE content_type
END;

UPDATE announcement_refused_types SET content_type = CASE content_type
    WHEN 'Téléphones & hi-fi'    THEN 'Téléphone & électronique'
    WHEN 'Matériel informatique' THEN 'Téléphone & électronique'
    WHEN 'Électronique'          THEN 'Téléphone & électronique'
    WHEN 'Hi-fi'                 THEN 'Téléphone & électronique'
    WHEN 'Téléphone'             THEN 'Téléphone & électronique'
    WHEN 'Alim. sèche'           THEN 'Alimentation sèche'
    WHEN 'Nourriture'            THEN 'Alimentation sèche'
    WHEN 'Cosmétiques'           THEN 'Cosmétiques & parfums'
    WHEN 'Cosmét.'               THEN 'Cosmétiques & parfums'
    WHEN 'Vêtements'             THEN 'Vêtements & tissus'
    WHEN 'Médicaments'           THEN 'Médicaments traditionnels'
    WHEN 'Documents'             THEN 'Documents & administratif'
    WHEN 'Cadeaux'               THEN 'Cadeaux & jouets'
    WHEN 'Autres'                THEN 'Autre'
    ELSE content_type
END;

-- ─── 3. Déduplication : 'Hi-fi' et 'Téléphone' convergent vers le même libellé ──
DELETE FROM announcement_accepted_types a
WHERE a.ctid <> (SELECT MIN(b.ctid) FROM announcement_accepted_types b
                 WHERE b.announcement_id = a.announcement_id
                   AND b.content_type = a.content_type);

DELETE FROM announcement_refused_types a
WHERE a.ctid <> (SELECT MIN(b.ctid) FROM announcement_refused_types b
                 WHERE b.announcement_id = a.announcement_id
                   AND b.content_type = a.content_type);

-- ─── 4. bids.content_category : chaîne jointe par virgule ────────────────────
-- REPLACE successifs sur la chaîne complète. ORDRE CRITIQUE : du libellé le plus
-- long au plus court. Remplacer 'Téléphone' avant 'Téléphones & hi-fi' produirait
-- 'Téléphone & électroniques & hi-fi'.
UPDATE bids SET content_category =
    REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
    REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
        content_category,
        'Téléphones & hi-fi',    'Téléphone & électronique'),
        'Matériel informatique', 'Téléphone & électronique'),
        'Électronique',          'Téléphone & électronique'),
        'Médicaments',           'Médicaments traditionnels'),
        'Cosmétiques',           'Cosmétiques & parfums'),
        'Alim. sèche',           'Alimentation sèche'),
        'Nourriture',            'Alimentation sèche'),
        'Téléphone',             'Téléphone & électronique'),
        'Cosmét.',               'Cosmétiques & parfums'),
        'Vêtements',             'Vêtements & tissus'),
        'Documents',             'Documents & administratif'),
        'Cadeaux',               'Cadeaux & jouets'),
        'Hi-fi',                 'Téléphone & électronique'),
        'Autres',                'Autre')
WHERE content_category IS NOT NULL;
```

**Attention, deux pièges que l'implémenteur doit vérifier et corriger si besoin :**

1. **Non-idempotence potentielle du bloc 4.** `REPLACE('Téléphone', 'Téléphone & électronique')` appliqué une 2ᵉ fois sur `Téléphone & électronique` produirait `Téléphone & électronique & électronique`. **Le test d'idempotence du Step 1 le détectera.** Correctif attendu : n'appliquer les `REPLACE` que sur les valeurs non encore migrées, par exemple en encadrant chaque item de délimiteurs, ou plus simplement en n'exécutant le bloc 4 que `WHERE content_category NOT LIKE '%Téléphone & électronique%' …` — ou, plus robuste, en décomposant/recomposant la chaîne (`string_to_array` → `unnest` → `CASE` d'égalité exacte → `string_agg`), ce qui supprime totalement le problème d'ordre ET d'idempotence. **Cette dernière approche est recommandée** : elle réutilise exactement le même `CASE` d'égalité exacte que les blocs 2 et 3.
2. Vérifier que `Médicaments` → `Médicaments traditionnels` est bien idempotent (rejouer produirait `Médicaments traditionnels traditionnels`) — même remède.

Écrire le bloc 4 avec `string_to_array`/`string_agg` dès le départ est le choix sûr :

```sql
UPDATE bids SET content_category = (
    SELECT string_agg(
        CASE trim(item)
            WHEN 'Téléphones & hi-fi'    THEN 'Téléphone & électronique'
            WHEN 'Matériel informatique' THEN 'Téléphone & électronique'
            WHEN 'Électronique'          THEN 'Téléphone & électronique'
            WHEN 'Hi-fi'                 THEN 'Téléphone & électronique'
            WHEN 'Téléphone'             THEN 'Téléphone & électronique'
            WHEN 'Alim. sèche'           THEN 'Alimentation sèche'
            WHEN 'Nourriture'            THEN 'Alimentation sèche'
            WHEN 'Cosmétiques'           THEN 'Cosmétiques & parfums'
            WHEN 'Cosmét.'               THEN 'Cosmétiques & parfums'
            WHEN 'Vêtements'             THEN 'Vêtements & tissus'
            WHEN 'Médicaments'           THEN 'Médicaments traditionnels'
            WHEN 'Documents'             THEN 'Documents & administratif'
            WHEN 'Cadeaux'               THEN 'Cadeaux & jouets'
            WHEN 'Autres'                THEN 'Autre'
            ELSE trim(item)
        END, ', ' ORDER BY ord)
    FROM unnest(string_to_array(bids.content_category, ',')) WITH ORDINALITY AS t(item, ord)
)
WHERE content_category IS NOT NULL AND content_category <> '';
```

Égalité exacte par item, ordre préservé, **idempotent par construction** (un libellé canonique retombe dans le `ELSE`), texte libre préservé. Utiliser cette version.

- [ ] **Step 4: Vérifier que le test passe**

Run: `cd dony-back && ./mvnw test -Dtest=V171ContentCategoriesMigrationTest`
Expected: PASS, y compris l'assertion d'idempotence.

- [ ] **Step 5: Suite complète**

Run: `cd dony-back && ./mvnw test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V171__unify_content_categories.sql src/test/java/com/dony/api/migrations/V171ContentCategoriesMigrationTest.java
git commit -m "feat(contenus): migration V171 — normalisation du vocabulaire des types de contenu"
```

---

### Task 3: dony-pro — endpoint consommé sous sa nouvelle forme

**Files (dony-pro):**
- Modify: `app/features/trajets/services/configService.ts`
- Modify: `app/features/trajets/components/NewAnnouncementForm.vue` (~lignes 34, 71-80, 440-451)
- Modify: `app/features/trajets/components/ContentTagChips.vue`
- Modify: `app/features/trajets/data/tripTemplates.ts:25`
- Test: fichiers de test correspondants sous `tests/unit/features/trajets/`

**Interfaces:**
- Consomme (Task 1) : `GET /config/content-categories` → `[{code, label, emoji}]` (11 entrées).
- Produit (Task 4) : un moyen partagé de récupérer le catalogue — exporter depuis `configService.ts` :
  ```typescript
  export interface ContentCategory { code: string; label: string; emoji: string }
  export function fetchContentCategories(): Promise<ContentCategory[]>
  ```

**Contexte.** `fetchContentCategories()` renvoie aujourd'hui `string[]` et alimente les presets de `ContentTagChips` dans `NewAnnouncementForm.vue`. C'est le **seul consommateur de l'endpoint dans tout le monorepo** — la forme change, il doit suivre. `REFUSED_PRESETS` y est un tableau vide en dur (`const REFUSED_PRESETS: string[] = []`) : il doit désormais recevoir le même catalogue que les acceptés (la spec demande une sélection dans le catalogue pour « ce que je refuse » aussi, en gardant la saisie libre).

- [ ] **Step 1: Créer la branche**

```bash
cd dony-pro && git checkout main && git pull && git checkout -b feature/vocabulaire-contenus-unifie
```

- [ ] **Step 2: Écrire les tests qui échouent**

Adapter/écrire les tests unitaires de `configService` : `fetchContentCategories()` renvoie des objets `{code,label,emoji}` (mocker la réponse HTTP avec 2-3 entrées du catalogue), et le composant `NewAnnouncementForm` passe bien `label` aux chips (acceptés ET refusés). Suivre le style des tests existants du dossier — **les fichiers réels font foi** (vitest + @vue/test-utils).

Valeurs à utiliser dans les mocks (verbatim du catalogue) :
```typescript
[
  { code: 'DOCUMENTS', label: 'Documents & administratif', emoji: '📄' },
  { code: 'PRODUITS_FRAIS', label: 'Produits frais / périssables', emoji: '🐟' },
  { code: 'VETEMENTS', label: 'Vêtements & tissus', emoji: '👗' },
]
```

- [ ] **Step 3: Lancer les tests, vérifier l'échec**

Run: `cd dony-pro && pnpm vitest run tests/unit/features/trajets`
Expected: FAIL — le service renvoie encore `string[]`.

- [ ] **Step 4: Implémenter**

- `configService.ts` : exporter l'interface `ContentCategory` et typer `fetchContentCategories(): Promise<ContentCategory[]>`.
- `NewAnnouncementForm.vue` : le preset des chips acceptées devient `categories.map(c => c.label)` ; **`REFUSED_PRESETS` (tableau vide en dur) est remplacé par le même catalogue** — les deux sections proposent désormais le catalogue et gardent leur saisie libre.
- `ContentTagChips.vue` : si le composant peut afficher l'emoji devant le libellé sans casser sa valeur (la valeur émise doit rester le **label seul**, jamais `emoji + label` — c'est cette valeur qui est persistée), le faire ; sinon laisser l'affichage tel quel. **Ne jamais émettre l'emoji dans la valeur.**
- `tripTemplates.ts:25` : `DEFAULT_CATEGORIES = ['Vêtements & tissus', 'Documents & administratif', 'Cosmétiques & parfums']` (libellés canoniques).

- [ ] **Step 5: Vérifier**

Run: `cd dony-pro && pnpm vitest run && pnpm typecheck`
Expected: tous verts, aucune régression.

- [ ] **Step 6: Commit**

```bash
git add app/features/trajets tests/
git commit -m "feat(contenus): dony-pro consomme le catalogue {code,label,emoji}"
```

---

### Task 4: dony-pro — liste déroulante des types de contenu dans les règles d'automatisation

**Files (dony-pro):**
- Modify: `app/features/automations/components/AutomationRuleModal.vue` (champ `value`, ~lignes 220-226)
- Test: `tests/unit/features/automations/AutomationRuleModal.spec.ts` (créer si absent)

**Interfaces:**
- Consomme (Task 3) : `fetchContentCategories(): Promise<ContentCategory[]>` depuis `app/features/trajets/services/configService.ts`.

**Contexte — c'est le correctif du bug d'origine côté saisie.** Aujourd'hui, le champ `value` d'une condition est un `<input type="text">` **générique, identique pour les 6 champs** (`sender_rating`, `weight_kg`, `corridor`, `content_type`, `capacity_free_kg`, `hours_before_departure`) : aucune logique conditionnelle sur `condition.field`. Un voyageur y tape « Poissons » et crée une règle qui ne matchera jamais rien.

**Comportement attendu :**

- Quand `condition.field === 'content_type'` : afficher un `<select>` peuplé par le catalogue (`label` en valeur ET en affichage — la valeur émise doit être le **label**, car c'est ce qui est comparé à `bid.contentCategory` par le moteur), plus une option finale « Autre valeur… » qui bascule sur un `<input type="text">` libre (un voyageur doit pouvoir cibler un type hors catalogue).
- Pour tous les autres `field` : `<input type="text">` inchangé.
- Changer de `field` après avoir saisi une valeur doit **réinitialiser la valeur** (sinon on garde un « 4.5 » sur un `content_type`).
- Le catalogue est chargé une fois à l'ouverture du modal. En cas d'échec de l'appel, retomber sur le `<input type="text">` libre plutôt que bloquer la création de règle.

- [ ] **Step 1: Écrire les tests qui échouent**

Tests attendus (style vitest + @vue/test-utils, **le fichier réel des tests d'automations fait foi** — voir `tests/unit/features/automations/`) :

1. `field = content_type` → un `<select>` est rendu, contenant les 11 libellés du catalogue.
2. Sélectionner « Produits frais / périssables » émet la condition avec `value: 'Produits frais / périssables'` (le **label**, pas le code, pas l'emoji).
3. Choisir « Autre valeur… » fait apparaître un `<input type="text">` et permet de saisir « Poissons ».
4. `field = weight_kg` → un `<input type="text">` (pas de `<select>`).
5. Passer de `content_type` à `weight_kg` réinitialise `value` à une chaîne vide.
6. Si `fetchContentCategories` rejette, un `<input type="text">` est rendu pour `content_type` (repli, pas de blocage).

- [ ] **Step 2: Lancer les tests, vérifier l'échec**

Run: `cd dony-pro && pnpm vitest run tests/unit/features/automations`
Expected: FAIL — aucun `<select>` n'est rendu, le champ est un input générique.

- [ ] **Step 3: Implémenter**

Dans `AutomationRuleModal.vue` : charger le catalogue au montage (`onMounted`), stocker dans un `ref<ContentCategory[]>([])` et un `ref<boolean>` de repli en cas d'erreur ; rendre le champ `value` conditionnellement selon `condition.field` ; réinitialiser `condition.value` dans le handler de changement de `field`.

- [ ] **Step 4: Vérifier**

Run: `cd dony-pro && pnpm vitest run && pnpm typecheck && pnpm lint`
Expected: verts.

- [ ] **Step 5: Commit**

```bash
git add app/features/automations tests/
git commit -m "feat(contenus): liste déroulante des types de contenu dans les règles d'automatisation"
```

---

### Task 5: dony_app — repository du catalogue (source unique côté Flutter)

**Files (dony_app):**
- Create: `lib/features/content_categories/data/content_category_model.dart`
- Create: `lib/features/content_categories/data/content_category_datasource.dart`
- Create: `lib/features/content_categories/data/content_category_repository.dart`
- Create: `lib/features/content_categories/presentation/content_category_selector.dart`
- Test: `test/features/content_categories/content_category_repository_test.dart`, `test/features/content_categories/content_category_selector_test.dart`

**Interfaces:**
- Consomme (Task 1) : `GET /config/content-categories` → `[{code, label, emoji}]`.
- Produit (Tasks 6 et 7) :
  ```dart
  class ContentCategory {
    final String code;    // clé technique — JAMAIS envoyée au backend
    final String label;   // valeur envoyée et stockée
    final String emoji;
  }

  class ContentCategoryRepository {
    /// Catalogue depuis l'API, mis en cache en mémoire pour la session.
    /// En cas d'échec réseau, retourne [fallbackCatalog] — ne lève jamais :
    /// un formulaire ne doit jamais être bloqué par cet appel.
    Future<List<ContentCategory>> getCategories();
  }

  /// Catalogue embarqué, identique au catalogue backend. Filet de sécurité hors ligne.
  const List<ContentCategory> fallbackCatalog = [ /* les 11 catégories */ ];

  /// Icône locale par code, avec repli générique (Icons.inventory_2_rounded)
  /// pour tout code inconnu — une nouvelle catégorie backend s'affiche donc
  /// sans nécessiter une release mobile.
  IconData iconForCode(String code);
  ```
- **Widget de sélection multiple réutilisable** (`ContentCategorySelector`) : liste déroulante à cocher alimentée par le repository + champ « Ajouter un autre type… » pour la saisie libre. Émet une `List<String>` de **labels** (canoniques et/ou libres). C'est lui que les Tasks 6 et 7 branchent partout.

**Contexte.** L'enum `ContentCategory` actuel (`lib/features/package_request/data/models/content_category.dart`) porte `wireName` (`'VETEMENTS'`), `label`, `emoji`, `IconData`. Il est **supprimé en Task 6**. Ici, on construit son remplaçant piloté par le backend. Les `IconData` ne peuvent pas venir de l'API (type Flutter) : d'où `iconForCode`, avec repli.

**Catalogue de repli embarqué — valeurs verbatim (identiques au backend) :** voir le tableau des Global Constraints (code, label, emoji, dans cet ordre).

- [ ] **Step 1: Créer la branche**

```bash
cd dony_app && git checkout main && git pull && git checkout -b feature/vocabulaire-contenus-unifie
```

**⚠️ Baseline de tests.** Le projet a des tests pré-existants en échec sur `main` (note du ledger : ~108, dans `test/features/matching/presentation/` et `envoyer_hub_screen_test.dart`). **Avant toute modification, lancer `flutter test` et noter le nombre d'échecs.** Tout échec supplémentaire est imputable à ce chantier ; les échecs pré-existants ne le sont pas.

- [ ] **Step 2: Écrire les tests qui échouent**

`content_category_repository_test.dart` :
1. L'API renvoie 11 catégories → `getCategories()` les retourne, `code`/`label`/`emoji` correctement désérialisés.
2. Deuxième appel → **aucun second appel HTTP** (cache mémoire).
3. L'API échoue (`DioException`) → `getCategories()` retourne `fallbackCatalog` (11 entrées) et **ne lève pas**.
4. `iconForCode('DOCUMENTS')` retourne une icône ; `iconForCode('CODE_INCONNU')` retourne l'icône générique (pas de crash).

`content_category_selector_test.dart` :
5. Affiche les 11 libellés du catalogue.
6. Cocher deux catégories émet `['Documents & administratif', 'Livres']` (labels).
7. Saisir « Poissons » dans le champ libre l'ajoute à la sélection émise.
8. Les valeurs déjà sélectionnées passées en entrée sont pré-cochées, y compris une valeur libre hors catalogue.

- [ ] **Step 3: Lancer, vérifier l'échec**

Run: `cd dony_app && flutter test test/features/content_categories/`
Expected: FAIL — les fichiers n'existent pas.

- [ ] **Step 4: Implémenter**

Suivre les conventions du projet (feature-first, `data/` + `presentation/`, Dio, pas de `setState` — BLoC ou `ValueNotifier` selon ce que fait le widget voisin ; **les fichiers réels font foi**). Le datasource appelle `/config/content-categories` via le client Dio existant (voir `lib/features/.../config_datasource.dart`, qui appelle déjà `/config/commission-rate` — **réutiliser ce client**).

- [ ] **Step 5: Vérifier**

Run: `cd dony_app && flutter test test/features/content_categories/ && flutter analyze`
Expected: verts.

- [ ] **Step 6: Commit**

```bash
git add lib/features/content_categories test/features/content_categories
git commit -m "feat(contenus): repository du catalogue de types de contenu + sélecteur réutilisable"
```

---

### Task 6: dony_app — écrans colis (bid + demande de colis)

**Files (dony_app):**
- Modify: `lib/features/matching/presentation/widgets/create_bid_bottom_sheet.dart` (supprimer `_contentCategories` ligne ~34, brancher le sélecteur)
- Modify: `lib/features/matching/presentation/screens/create_bid_screen.dart` (supprimer `_contentCategories` ligne ~25, brancher le sélecteur)
- Modify: `lib/features/package_request/presentation/screens/sender/create_wizard/steps/step_2_details.dart` (~lignes 43-76)
- Delete: `lib/features/package_request/data/models/content_category.dart` (l'enum)
- Modify: tout fichier important cet enum (repository de package_request, modèles) — **`grep -rn "ContentCategory" lib/` pour les trouver tous**
- Test: `test/features/matching/presentation/create_bid_screen_test.dart:216-230` (réécrire), tests du wizard

**Interfaces:**
- Consomme (Task 5) : `ContentCategoryRepository`, `ContentCategorySelector`, `iconForCode`.

**Contexte — deux changements de contrat de données :**

1. **Le bid** envoie déjà `contentCategory: categories.join(', ')` (String, libellés joints par virgule). **Ce format ne change pas** — seule la source de la liste change. Rien à modifier côté envoi.
2. **La demande de colis** envoie aujourd'hui `contentCategory: contentCategory.wireName` — soit un **code** (`'VETEMENTS'`), et **une seule catégorie**. Après ce chantier, elle doit envoyer un **libellé** (`'Vêtements & tissus'`), et le wizard passe en **sélection multiple** comme le bid (format joint par virgule). La migration V171 (Task 2) a converti l'existant en base. **Vérifier le DTO backend `PackageRequestCreateRequest.contentCategory` (`@Size(max=255)`)** : les libellés canoniques joints tiennent-ils dans 255 caractères ? La colonne est `VARCHAR(255)` (élargie par V143). Si un utilisateur cochait les 11 catégories, la chaîne ferait environ 250 caractères — **c'est à la limite**. Ajouter côté Flutter une limite de sélection (par exemple 5 catégories) OU vérifier la longueur avant envoi. Signaler ce point en revue si une autre solution semble meilleure.

- [ ] **Step 1: Écrire/réécrire les tests**

- `create_bid_screen_test.dart:216-230` : le test `'affiche les 7 chips de catégorie'` recopie la liste en dur. **Le réécrire** pour vérifier que l'écran affiche le catalogue fourni par un repository mocké (et non une liste figée), et qu'une saisie libre est possible.
- Wizard de demande de colis : sélection multiple, envoi de libellés joints par virgule, saisie libre.

- [ ] **Step 2: Lancer, vérifier l'échec**

Run: `cd dony_app && flutter test test/features/matching/presentation/create_bid_screen_test.dart test/features/package_request/`
Expected: FAIL.

- [ ] **Step 3: Implémenter**

Supprimer les deux constantes `_contentCategories`, supprimer l'enum, brancher `ContentCategorySelector`. `grep -rn "ContentCategory\|_contentCategories" lib/` doit ne plus rien retourner hors de `lib/features/content_categories/`.

- [ ] **Step 4: Vérifier**

Run: `cd dony_app && flutter test && flutter analyze`
Expected: aucun échec **au-delà de la baseline notée au Step 1 de la Task 5**.

- [ ] **Step 5: Commit**

```bash
git add lib/ test/
git commit -m "feat(contenus): écrans colis branchés sur le catalogue unifié"
```

---

### Task 7: dony_app — écrans trajet, recherche, modèle, alerte corridor

**Files (dony_app):**
- Modify: `lib/features/matching/presentation/widgets/create_announcement/_create_announcement_constants.dart:8` (supprimer `kContentTypes`)
- Modify: `lib/features/matching/presentation/widgets/create_announcement/prix_conditions_step.dart` (~lignes 588-730 : « Ce que j'accepte » **et** « Ce que je refuse »)
- Modify: `lib/features/matching/presentation/widgets/search_form_bottom_sheet.dart:18` (supprimer `_contentTypes`)
- Modify: `lib/features/trip_templates/presentation/screens/trip_template_edit_screen.dart:21` (supprimer `_contentTypes`)
- Modify: `lib/features/corridor_alerts/presentation/widgets/corridor_alert_form_sheet.dart:17` (supprimer `_kAlertContentTypes`)
- Test: `test/features/matching/presentation/widgets/create_announcement/prix_conditions_step_test.dart` (~ligne 280 itère déjà sur `kContentTypes` — l'adapter au catalogue), + tests des 3 autres écrans (**aucun n'en a aujourd'hui : en créer au moins un par écran**)

**Interfaces:**
- Consomme (Task 5) : `ContentCategoryRepository`, `ContentCategorySelector`.

**Contexte.** C'est l'écran de la capture d'écran du porteur produit : « Publier un trajet » → « Ce que j'accepte » (chips figées + champ « Ajouter un autre type… ») et « Ce que je refuse » (**champ libre pur, aucune proposition**). Les deux sections doivent désormais proposer le catalogue complet en liste déroulante, tout en gardant la saisie libre — c'est la demande explicite.

Quatre listes en dur disparaissent ici (C, D, E, F). **La liste F (alerte corridor) contient « Électronique » et « Nourriture », qui n'existent nulle part ailleurs** : après bascule sur le catalogue, ces valeurs disparaissent de l'UI ; la migration V171 a déjà converti les données correspondantes (`Électronique` → `Téléphone & électronique`, `Nourriture` → `Alimentation sèche`).

- [ ] **Step 1: Écrire les tests qui échouent**

Pour chacun des 4 écrans : le catalogue (fourni par un repository mocké) est affiché, une saisie libre est possible, la valeur émise est une liste de **labels**. Pour `prix_conditions_step`, couvrir les **deux** sections (accepte ET refuse).

- [ ] **Step 2: Lancer, vérifier l'échec**

Run: `cd dony_app && flutter test test/features/matching/presentation/widgets/create_announcement/ test/features/trip_templates/ test/features/corridor_alerts/`
Expected: FAIL.

- [ ] **Step 3: Implémenter**

Supprimer les 4 constantes, brancher `ContentCategorySelector` dans les 5 emplacements (les deux sections de `prix_conditions_step` comptent double). Après cette tâche, `grep -rn "kContentTypes\|_contentTypes\|_kAlertContentTypes" lib/` ne doit plus rien retourner.

- [ ] **Step 4: Vérifier**

Run: `cd dony_app && flutter test && flutter analyze`
Expected: aucun échec au-delà de la baseline.

- [ ] **Step 5: Commit**

```bash
git add lib/ test/
git commit -m "feat(contenus): écrans trajet, recherche, modèle et alerte branchés sur le catalogue unifié"
```

---

## Vérification finale (avant les PRs)

- [ ] `grep -rn "_contentCategories\|kContentTypes\|_contentTypes\|_kAlertContentTypes\|ContentCategory\." dony_app/lib/` ne retourne rien hors de `lib/features/content_categories/`.
- [ ] `grep -rn "content-categories" dony-back/src/main/resources/application.yml` ne retourne rien.
- [ ] `BidContentRules.java` et `CustomRuleConditionEvaluator.java` sont **inchangés** (`git diff main -- <ces deux fichiers>` est vide).
- [ ] dony-back : `./mvnw test` → BUILD SUCCESS.
- [ ] dony-pro : `pnpm vitest run && pnpm typecheck && pnpm lint` → verts.
- [ ] dony_app : `flutter test && flutter analyze` → aucun échec au-delà de la baseline `main`.
- [ ] Trois PRs : dony-back, dony-pro, dony_app. **Ordre de merge imposé : dony-back en premier** (les fronts consomment sa nouvelle forme de réponse, qui est un changement cassant).
