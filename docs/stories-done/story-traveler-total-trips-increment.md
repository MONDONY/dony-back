# Story — Incrémentation `total_trips` du voyageur (Backend)

**Date:** 2026-05-03
**Status:** ✅ Complète

## Résumé
Branche le compteur `users.total_trips` (déjà persisté en base depuis V29 mais jamais alimenté) sur l'événement `DeliveryConfirmedEvent`. **Un voyage physique = un trajet** : `total_trips` est incrémenté de +1 lorsque le **premier** bid d'une `announcement` passe `COMPLETED`. Les bids COMPLETED suivants sur la même annonce ne déclenchent plus d'incrément. Une annonce sans aucun bid COMPLETED ne compte pas.

## Règle métier

| Scénario | total_trips |
|---|---|
| Annonce créée, 0 colis livré | +0 |
| 1er colis livré (scan QR / code de remise) sur une annonce | **+1** |
| 2ème colis livré sur la **même** annonce | +0 |
| 3ème colis livré sur la **même** annonce | +0 |
| Nouvelle annonce, 1er colis livré dessus | **+1** |

Le passage en `COMPLETED` est déclenché par le scan QR du destinataire ou la saisie du code de remise — c'est la seule preuve technique que le voyageur est physiquement arrivé à destination. Une livraison réussie suffit à prouver l'arrivée du voyage entier ; zéro livraison = zéro preuve = zéro incrément.

## Fichiers créés
- `src/main/java/com/dony/api/auth/TravelerStatsListener.java` — listener `@TransactionalEventListener(AFTER_COMMIT)` qui incrémente `users.total_trips` et marque l'annonce comme comptabilisée.
- `src/main/resources/db/migration/V39__add_total_trips_counted_flag_to_announcements.sql` — ajoute le flag d'idempotence `announcements.total_trips_counted`.
- `src/test/java/com/dony/api/auth/TravelerStatsListenerTest.java` — 7 tests unitaires Mockito.
- `src/test/java/com/dony/api/auth/TravelerStatsListenerIT.java` — 5 tests d'intégration `@SpringBootTest`.

## Fichiers modifiés
- `src/main/java/com/dony/api/matching/AnnouncementEntity.java` — nouveau champ `boolean totalTripsCounted` mappé sur la colonne `total_trips_counted` (default false), avec getter/setter.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. Le destinataire scanne le QR (ou le voyageur saisit le code de remise) → `TrackingService.confirmDelivery(...)`.
2. Le bid passe en `BidStatus.COMPLETED` et est sauvegardé.
3. `TrackingService` publie `DeliveryConfirmedEvent(bidId, senderId, travelerId)`.
4. **Après le commit de la transaction de tracking**, `TravelerStatsListener.onDeliveryConfirmed` se déclenche dans une nouvelle transaction (`REQUIRES_NEW`).
5. Le listener charge le bid → vérifie son statut → charge l'annonce → vérifie le flag d'idempotence → charge le voyageur → incrémente `total_trips` → marque `announcement.totalTripsCounted = true` → écrit une entrée `audit_log`.

### Points d'entrée API
Aucun nouvel endpoint. Feature purement événementielle, déclenchée par les endpoints existants de confirmation de livraison.

### Entités JPA impliquées
- `AnnouncementEntity` (`announcements`) — nouveau champ `total_trips_counted BOOLEAN NOT NULL DEFAULT FALSE`. Sert de verrou logique : une fois à `true`, plus aucun bid sur cette annonce ne peut redéclencher l'incrément. C'est ce flag qui matérialise la règle « un voyage = un trajet ».
- `BidEntity` (`bids`) — inchangée (le flag avait été mis là dans une première version, déplacé sur `announcements` après changement de logique métier).
- `UserEntity` (`users`) — colonne `total_trips` (existait depuis V29, désormais alimentée).

### Logique métier critique
- **Idempotence par annonce, pas par bid** : c'est ce qui garantit qu'un voyage avec 3 colis livrés ne compte que pour 1 trajet.
- **Phase AFTER_COMMIT** : si la transaction de `confirmDelivery` rollback, le listener ne fait rien — pas de phantom counter.
- **Garde défensive sur `BidStatus`** : le listener vérifie aussi que `bid.status == COMPLETED` avant tout traitement. Théoriquement redondant (l'event n'est publié qu'à ce moment), mais protège contre un futur refacto qui republierait l'event ailleurs.
- **Stratégie « voyageur introuvable »** : si `userRepository.findById(travelerId)` retourne vide, on **marque quand même l'annonce `totalTripsCounted = true`** pour empêcher tout rejeu infini. Cas considéré comme une anomalie métier (un voyageur supprimé qui a livré un colis), donc loggé en `ERROR` pour remontée Sentry et investigation manuelle.
- **Annonce introuvable** : log warn + return sans rien marquer — cas qui ne devrait jamais se produire, le bid ayant une FK implicite vers son announcement.

### Events Spring publiés / écoutés
- **Écouté** : `com.dony.api.tracking.events.DeliveryConfirmedEvent` (publié par `TrackingService.confirmDelivery`, `TrackingService.java:419`).
- **Publié** : aucun.
- **Audit** : `audit_log` reçoit une entrée `entityType=USER`, `action=TOTAL_TRIPS_INCREMENTED`, payload `{bidId, announcementId, newTotal}`.

### Pièges et points d'attention
- **`@Transactional(REQUIRES_NEW)` est obligatoire** avec `AFTER_COMMIT` : sans ça, les saves échouent silencieusement (aucune transaction active après le commit du parent).
- **Le flag est sur `announcements`, pas sur `bids`** — c'est le point crucial. Si on met le flag sur `bids`, on incrémente une fois par colis livré (option A), ce qui n'est pas la règle métier.
- **Bids existants au déploiement** : tous les bids déjà `COMPLETED` ne déclencheront plus l'event, donc les annonces correspondantes resteront à `total_trips_counted = false`. Si on souhaite rattraper rétroactivement les voyages historiques, prévoir un script ad-hoc qui : (1) trouve les annonces ayant ≥1 bid COMPLETED, (2) incrémente `users.total_trips` pour leur traveler, (3) flag les annonces.
- **Test d'intégration en H2** : `application-test.yml` utilise `ddl-auto: create-drop` et désactive Flyway, donc la colonne est créée à partir de l'entité JPA. La migration V39 est exécutée en dev/prod via Flyway.

## Critères d'acceptation couverts
- [x] L'event `DeliveryConfirmedEvent` est écouté → ✅ `@TransactionalEventListener` dans `TravelerStatsListener`.
- [x] **1er colis livré sur une annonce → +1** → ✅ couvert par `increments_total_trips_after_commit_on_first_bid`.
- [x] **3 colis livrés sur la même annonce → +1 seul** → ✅ couvert par `three_bids_completed_on_same_announcement_increment_only_once`.
- [x] **Annonce sans bid COMPLETED → +0** → ✅ couvert par `announcement_with_zero_completed_bid_does_not_increment`.
- [x] **Nouvelle annonce → ré-incrémente** → ✅ couvert par `new_announcement_increments_again`.
- [x] **Pas d'incrément si la transaction parente rollback** → ✅ phase `AFTER_COMMIT`, couvert par `does_not_increment_when_parent_transaction_rolls_back`.
- [x] **Garde sur `bid.status != COMPLETED`** → ✅ couvert par `skips_increment_if_bid_status_not_completed`.
- [x] **Voyageur introuvable → marquage défensif + log ERROR** → ✅ couvert par `traveler_not_found_marks_announcement_counted_and_logs_error`.
- [x] **Audit trail** → ✅ entrée `TOTAL_TRIPS_INCREMENTED` (avec `bidId`, `announcementId`, `newTotal`) vérifiée dans le test unitaire.

## Tests
- `./mvnw test` → **411 tests, 0 failure, 0 erreur, BUILD SUCCESS**.
- Tests ajoutés :
  - `com.dony.api.auth.TravelerStatsListenerTest` (7 tests unitaires Mockito).
  - `com.dony.api.auth.TravelerStatsListenerIT` (5 tests d'intégration `@SpringBootTest`).

## Décisions techniques
- **Listener placé dans `auth/`** (et non `matching/` ou `tracking/`) : cohérent avec le fait que `total_trips` est un attribut de `UserEntity`.
- **Flag sur `announcements` plutôt que sur `bids`** : matérialise directement la règle métier « un voyage = un trajet ». Première version mettait le flag sur `bids`, ce qui correspondait à « un colis = un trajet » (option A) — déplacé après clarification du PO.
- **Pas de compteur dérivé `COUNT(...)`** : choix explicite du compteur stocké, validé par le PO. Évite les requêtes lourdes à chaque affichage du profil voyageur.
- **`AFTER_COMMIT` + `REQUIRES_NEW`** plutôt que `@EventListener` synchrone in-transaction : élimine le risque d'incrément orphelin si le tracking rollback. Trade-off : si la nouvelle transaction du listener échoue, l'incrément est perdu (mais loggé) — acceptable car non critique financièrement.
