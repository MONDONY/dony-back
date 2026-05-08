# Story 3.4 — Mode de transport sur les annonces (Backend)

**Date:** 2026-05-02
**Status:** ✅ Complète

---

## Résumé

Ajout d'un champ `transportMode` sur les annonces de trajet (`PLANE` | `CAR` | `TRAIN` | `BUS` | `BOAT` | `OTHER`). Phase A d'un refactor en deux phases : Phase A persiste et expose le champ via l'API (création/modification/recherche/détail/bid embarqué), Phase B (à venir) utilisera la valeur pour afficher des icônes spécifiques sur la carte des annonces. Le champ est informatif côté métier (aucune règle ne dépend de la valeur en Phase A).

---

## Fichiers créés

- `src/main/resources/db/migration/V35__announcements_add_transport_mode.sql` — ajoute la colonne `transport_mode VARCHAR(20) NOT NULL` avec `CHECK` constraint sur les 6 valeurs autorisées et backfill des annonces existantes à `OTHER`.
- `src/main/java/com/dony/api/matching/TransportMode.java` — enum Java avec les 6 modalités, mappé en `EnumType.STRING` côté JPA.

## Fichiers modifiés

- `src/main/java/com/dony/api/matching/AnnouncementEntity.java` — ajout du champ `transportMode` (`@Enumerated(EnumType.STRING)`, `@Column(nullable = false)`).
- `src/main/java/com/dony/api/matching/dto/AnnouncementRequest.java` — ajout du champ `transportMode` avec `@NotNull` (Bean Validation → 422 si manquant).
- `src/main/java/com/dony/api/matching/dto/AnnouncementResponse.java` — ajout du champ `transportMode` exposé en sortie.
- `src/main/java/com/dony/api/matching/dto/AnnouncementSearchResponse.java` — ajout du champ pour la liste paginée.
- `src/main/java/com/dony/api/matching/dto/AnnouncementDetailResponse.java` — ajout du champ pour l'écran détail.
- `src/main/java/com/dony/api/matching/dto/BidResponse.java` — ajout du champ embarqué pour que l'expéditeur voie le mode de transport directement depuis ses bids.
- `src/main/java/com/dony/api/matching/AnnouncementService.java` — `create()` persiste `transportMode` + audit ; `update()` détecte le changement et logue `transportMode_old`/`transportMode_new` ; les 4 mappers (`toResponse`, `toSearchResponse`, `toDetailResponse`, `toMyAnnouncementResponse`) propagent le champ.
- `src/main/java/com/dony/api/matching/BidService.java` — le mapper `toBidResponse` lit `bid.getAnnouncement().getTransportMode()` et le pousse dans `BidResponse`.
- `src/main/java/com/dony/api/common/GlobalExceptionHandler.java` — nouveau handler `HttpMessageNotReadableException` → HTTP 400 (cas notamment d'une valeur d'enum invalide dans le JSON, ex: `"transportMode": "BIKE"`).
- `pom.xml` — fix de l'`argLine` JaCoCo : avant le fix, l'agent JaCoCo n'était pas attaché à la JVM de test (couverture rapportée à 0% sur tout le projet).
- `src/test/java/com/dony/api/matching/AnnouncementControllerIntegrationTest.java` — 4 tests ajoutés (création avec mode valide, refus si manquant, refus si invalide, update qui change le mode).
- `src/test/java/com/dony/api/matching/AnnouncementServiceTest.java` — 1 test ajouté pour vérifier l'audit `transportMode_old`/`transportMode_new` lors d'un update.

---

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

**Création** :
1. Le voyageur soumet `POST /api/v1/announcements` avec `transportMode: "PLANE"` dans le JSON.
2. Bean Validation refuse la requête si le champ est null (HTTP 422 via `MethodArgumentNotValidException` géré dans `GlobalExceptionHandler`).
3. Si la chaîne est invalide (`"BIKE"`), Jackson échoue à désérialiser l'enum → `HttpMessageNotReadableException` → HTTP 400.
4. `AnnouncementService.create()` mappe `request.transportMode()` → `AnnouncementEntity.transportMode` et sauvegarde.
5. `audit_log` reçoit une entrée `ANNOUNCEMENT_CREATED` avec un payload incluant `transportMode`.
6. Le mapper retourne `AnnouncementResponse` avec le champ.

**Modification** :
1. `PUT /api/v1/announcements/{id}` reçoit le nouveau payload (incluant `transportMode`).
2. `update()` compare `existing.getTransportMode()` vs `request.transportMode()`.
3. Si différent, audit log avec payload `{ transportMode_old: "CAR", transportMode_new: "TRAIN" }` (action `ANNOUNCEMENT_UPDATED`).
4. La nouvelle valeur est persistée.

**Lecture** :
- `GET /api/v1/announcements` (recherche) → `AnnouncementSearchResponse` avec `transportMode`.
- `GET /api/v1/announcements/{id}` → `AnnouncementDetailResponse` avec `transportMode`.
- `GET /api/v1/bids/{id}` → `BidResponse` avec `transportMode` (lu depuis l'annonce associée).

### Points d'entrée API

- `POST /api/v1/announcements` — `ROLE_TRAVELER`. `transportMode` requis (`@NotNull` sur le DTO). Valeur invalide → 400 (parse error) ; valeur null → 422 (validation).
- `PUT /api/v1/announcements/{id}` — `ROLE_TRAVELER`. Idem. Audit logué avec `transportMode_old`/`_new` si la valeur change.
- `GET /api/v1/announcements` — public/authentifié selon la story 3.5 ; renvoie `transportMode` dans chaque élément de `content`.
- `GET /api/v1/announcements/{id}` — détail, renvoie `transportMode`.
- `GET /api/v1/announcements/my` — annonces du voyageur connecté, renvoie `transportMode`.
- `GET /api/v1/bids/{id}` — `BidResponse` inclut `transportMode` lu depuis l'annonce parente.

### Entités JPA impliquées

- `AnnouncementEntity` → table `announcements` :
  - `transport_mode VARCHAR(20) NOT NULL` (V35)
  - `CHECK (transport_mode IN ('PLANE','CAR','TRAIN','BUS','BOAT','OTHER'))`
  - Mapping JPA : `@Enumerated(EnumType.STRING)` (jamais ORDINAL — on persiste le nom).

### Logique métier critique

- **Champ purement informatif en Phase A** : aucune règle métier (matching, paiement, tracking, cancellation) ne dépend de `transportMode`. Phase B utilisera la valeur uniquement pour rendre une icône sur la carte côté Flutter.
- **Backfill `OTHER`** : V35 met toutes les annonces pré-existantes à `OTHER` car la colonne est `NOT NULL`. Les voyageurs concernés peuvent éditer leurs annonces pour corriger.
- **Audit `old`/`_new` uniquement si la valeur change** : on ne logue pas une transition `PLANE → PLANE`. La comparaison se fait sur l'enum (`!equals`).

### Events Spring publiés / écoutés

Aucun event nouveau ou modifié dans cette story. Les events existants (`BidAcceptedEvent`, `TripCancelledEvent`, etc.) ne transportent pas `transportMode` — la valeur est lue à la demande depuis l'`AnnouncementEntity`.

### Pièges et points d'attention

- **Annonces pré-V35 = `OTHER`** : si un voyageur a publié avant la migration, son annonce affiche `OTHER` jusqu'à ce qu'il l'édite. À documenter côté ops si nécessaire.
- **Fix `argLine` dans `pom.xml` (critique)** : avant ce changement, le plugin JaCoCo générait bien `target/jacoco.exec` mais l'agent n'était pas branché sur la JVM Surefire. Résultat : le rapport indiquait 0% de couverture sur tout le projet. Le fix passe la propriété `argLine` (générée par `jacoco:prepare-agent`) à `maven-surefire-plugin` correctement.
- **Enum invalide → 400, pas 500** : sans le nouveau handler `HttpMessageNotReadableException`, une valeur comme `"BIKE"` remontait en `InternalServerError` côté client. Le handler explicite renvoie maintenant un `ProblemDetail` 400 RFC 7807. Cohérent avec REST conventions ("payload mal formé = 400").
- **Bean Validation `@NotNull` vs Jackson** : un champ absent ou `null` dans le JSON → 422 (validation Bean). Un champ avec une valeur de chaîne inconnue → 400 (parse Jackson). Les deux comportements sont volontairement différenciés.
- **Mapping JPA `EnumType.STRING` obligatoire** : si on utilisait `EnumType.ORDINAL`, ajouter une nouvelle valeur dans l'enum casserait toutes les annonces existantes (les ordinaux glisseraient).
- **`BidResponse.transportMode` lu via la relation `bid.announcement`** : si la relation est `LAZY` et que la session JPA est fermée, `LazyInitializationException`. Vérifier que le mapper s'exécute dans la transaction du service.

---

## Critères d'acceptation couverts

- [x] **Given** un voyageur KYC-vérifié, **When** il publie une annonce avec `transportMode = "PLANE"`, **Then** l'annonce est créée et l'audit log contient le mode (`AnnouncementService.create()` + audit).
- [x] **Given** un payload sans `transportMode`, **When** il poste, **Then** HTTP 422 avec violation `transportMode: must not be null` (Bean Validation).
- [x] **Given** un payload avec `transportMode = "BIKE"`, **When** il poste, **Then** HTTP 400 (handler `HttpMessageNotReadableException`).
- [x] **Given** une annonce existante en `CAR`, **When** le voyageur la modifie en `TRAIN`, **Then** la nouvelle valeur est persistée et l'audit log contient `transportMode_old: "CAR"`, `transportMode_new: "TRAIN"`.
- [x] **Given** une recherche `GET /announcements`, **Then** chaque élément de `content` expose `transportMode`.
- [x] **Given** un bid accepté, **When** l'expéditeur consulte le bid, **Then** `BidResponse.transportMode` contient le mode de l'annonce associée.

## Tests

- `./mvnw test` → **338 tests, BUILD SUCCESS, 0 failure** (4 integration tests + 1 service test ajoutés).
- `./mvnw test jacoco:report` → couverture sur les fichiers touchés :
  - `TransportMode` — **100%**
  - `AnnouncementEntity` — **100%**
  - `AnnouncementService` — **97.7%**
  - `BidService` — **91.7%**
  - `GlobalExceptionHandler` — **92%**
- Tests ajoutés/modifiés :
  - `AnnouncementControllerIntegrationTest` — `testCreateAnnouncement_WithTransportMode`, `testCreateAnnouncement_MissingTransportMode_Returns422`, `testCreateAnnouncement_InvalidTransportMode_Returns400`, `testUpdateAnnouncement_ChangeTransportMode`.
  - `AnnouncementServiceTest` — `testUpdate_TransportModeChanged_AuditLogsOldAndNew`.

## Décisions techniques

- **Enum + `CHECK` constraint plutôt que free string** : type-safety côté Java (impossible de persister une valeur hors-liste) et garantie au niveau DB (le `CHECK` rejette toute insertion via SQL direct hors enum). Plus robuste qu'un simple `VARCHAR` libre.
- **`OTHER` plutôt que `null`** pour les annonces backfilled : la colonne est `NOT NULL` (cohérent avec le futur usage UI où chaque annonce doit avoir une icône). `OTHER` joue le rôle de "valeur par défaut explicite" et permet de garder le champ obligatoire à la création.
- **Audit `old`/`_new` sur update, juste `transportMode` sur create** : à la création, il n'y a pas d'ancienne valeur, donc on logue uniquement la valeur posée. À l'update, la traçabilité fine `old → new` permet de reconstituer l'historique sans re-lire toutes les versions.
- **`@NotNull` sur DTO (Bean Validation)** : produit une réponse 422 avec un détail clair des violations, conformément au pattern utilisé par les autres champs (`departureCity`, etc.).
- **Nouveau handler `HttpMessageNotReadableException` → 400** : sans ça, une valeur d'enum invalide remontait en 500 (mauvaise UX et incorrect côté REST). Le 400 reflète "client a envoyé un payload mal formé / non parsable", cohérent avec les conventions HTTP.
- **Pas d'event nouveau** : `transportMode` est statique sur l'annonce et lisible à la demande. Aucun consommateur n'a besoin d'être notifié spécifiquement d'un changement de mode (Phase A informatif). Si Phase B introduit une logique métier, on pourra ajouter un event dédié.
