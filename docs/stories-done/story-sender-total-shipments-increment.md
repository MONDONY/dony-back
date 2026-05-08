# Story — Compteur `total_shipments` côté expéditeur (Backend + Flutter)

**Date:** 2026-05-03
**Status:** ✅ Complète

## Résumé
Ajout d'un compteur public `users.total_shipments` incrémenté à chaque colis livré (bid passé en COMPLETED). Symétrique du `total_trips` côté voyageur, avec idempotence stricte via le flag `bids.shipment_counted`. Exposé sur `/auth/me`, dans les `BidResponse` consultés par le voyageur, et affiché dans le profil expéditeur Flutter ainsi que sur la carte expéditeur du détail d'un bid.

## Règle métier (option a + β validée)

| Scénario | `total_shipments` |
|---|---|
| Bid créé, non livré | +0 |
| 1er colis livré (code de remise saisi par le destinataire) | +1 |
| Replay du `DeliveryConfirmedEvent` sur le même bid | +0 (idempotent) |
| 3 bids COMPLETED du même expéditeur sur des annonces différentes | +3 (un bid livré = +1) |
| Sender introuvable (anomalie) | +0, flag posé pour stopper les retries, log ERROR |

Une livraison réussie = +1 par bid. Pas d'agrégation par annonce (à la différence du voyageur où un voyage = +1 quel que soit le nombre de bids).

## Fichiers créés

### Backend
- `src/main/resources/db/migration/V40__add_total_shipments_and_counted_flag.sql` — colonnes `users.total_shipments` (INT NOT NULL DEFAULT 0) et `bids.shipment_counted` (BOOLEAN NOT NULL DEFAULT FALSE).
- `src/main/java/com/dony/api/auth/SenderStatsListener.java` — listener `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` qui consomme `DeliveryConfirmedEvent` et incrémente le compteur.
- `src/test/java/com/dony/api/auth/SenderStatsListenerTest.java` — 6 tests Mockito.
- `src/test/java/com/dony/api/auth/SenderStatsListenerIT.java` — 4 tests d'intégration `@SpringBootTest`.

## Fichiers modifiés

### Backend
- `src/main/java/com/dony/api/auth/UserEntity.java` — champ `int totalShipments` + getter/setter.
- `src/main/java/com/dony/api/matching/BidEntity.java` — champ `boolean shipmentCounted` + getter/setter.
- `src/main/java/com/dony/api/auth/dto/UserResponse.java` — nouveau champ `int totalShipments`.
- `src/main/java/com/dony/api/auth/AuthService.java` — `toResponse(...)` propage `getTotalShipments()`.
- `src/main/java/com/dony/api/matching/dto/BidResponse.java` — nouveau champ `Integer senderTotalShipments` (nullable si sender inconnu).
- `src/main/java/com/dony/api/matching/BidService.java` — `toResponse(...)` injecte `sender.getTotalShipments()`.

### Flutter
- `lib/features/auth/data/models/user_model.dart` — champ `final int totalShipments` (default 0), parsing JSON, props Equatable.
- `lib/features/matching/data/models/bid_model.dart` (+ `.g.dart`) — champ `final int? senderTotalShipments`.
- `lib/features/profile/presentation/profile_screen.dart` — la stat row "Envois" est désormais alimentée par `user.totalShipments` (auparavant `totalBids`, qui surfaçait le nombre de bids créés et non livrés).
- `lib/features/matching/presentation/screens/bid_detail_screen.dart` — la `_SenderCard` (visible côté voyageur quand il consulte un bid reçu) affiche désormais `X envoi(s)` avec icône truck.

## Comment ça fonctionne (pour la maintenance)

### Flux d'incrémentation
1. Le destinataire saisit le code de remise → `TrackingService.confirmDelivery(bidId)` :
   - vérifie le code, passe le bid en `COMPLETED`,
   - publie `DeliveryConfirmedEvent(bidId, senderId, travelerId)`.
2. La transaction de tracking commit.
3. **Deux** listeners `AFTER_COMMIT` réagissent :
   - `TravelerStatsListener` → incrémente `users.total_trips` (idempotent par annonce).
   - `SenderStatsListener` → incrémente `users.total_shipments` (idempotent par bid).
4. Chaque listener tourne dans une transaction `REQUIRES_NEW` indépendante.

### Logique du `SenderStatsListener`
1. Charge le `BidEntity` ; si introuvable → log WARN + return (no-op).
2. Si `bid.shipmentCounted == true` → no-op idempotent.
3. Garde défensive : si `bid.status != COMPLETED`, log WARN + return (l'event a fuité avant la transition).
4. Charge le `UserEntity` du sender ; si introuvable → marque le bid `shipmentCounted = true` pour stopper les retries, log ERROR (Sentry, investigation manuelle).
5. Sinon : `sender.totalShipments += 1`, save user, `bid.shipmentCounted = true`, save bid, audit `TOTAL_SHIPMENTS_INCREMENTED`.

### Idempotence et rollback
- `AFTER_COMMIT` : si la transaction d'origine `confirmDelivery` rollback, l'event n'est jamais consommé → pas de phantom increment (testé dans `SenderStatsListenerIT.does_not_increment_when_parent_transaction_rolls_back`).
- Flag `bids.shipment_counted` : protection contre le rejeu d'event Spring (testé dans `replay_of_event_does_not_double_count`).

### Exposition publique
- `GET /auth/me` → `UserResponse.totalShipments` (profil personnel).
- `GET /bids/...` → `BidResponse.senderTotalShipments` (visible des voyageurs qui consultent un bid reçu, pour leur donner confiance).

### Pièges
- Ne pas confondre avec `total_trips` (côté voyageur, idempotent par **annonce**) : ici on compte par **bid**, car un expéditeur n'a pas la notion de "voyage physique".
- Le flag `shipment_counted` est sur `bids` et non sur `announcements`.
- Un bid en `CANCELLED`, `REFUSED` ou `ACCEPTED` ne compte pas — seul `COMPLETED` déclenche l'event.
- L'audit_log est immuable : ne jamais tenter d'UPDATE/DELETE sur les entrées `TOTAL_SHIPMENTS_INCREMENTED`.

## Tests

### Backend
```
./mvnw test
[INFO] Tests run: 423, Failures: 0, Errors: 0, Skipped: 6
[INFO] BUILD SUCCESS
```
- `SenderStatsListenerTest` (6 tests Mockito) : increment, idempotence flag, bid introuvable, status ≠ COMPLETED, sender absent (flag posé sans incrément), flag posté après incrément.
- `SenderStatsListenerIT` (4 tests Spring) : commit standard, 3 bids du même sender = +3, replay event, rollback parent.

### Flutter
- Tests `auth/`, `profile/`, `matching/` (hors search_announcement_screen) : verts.
- 5 tests rouges pré-existants dans `search_announcement_screen_test.dart` (assertions sur icônes liste/carte) — sans rapport avec ce travail.

## Décisions techniques
- **Comptage par bid plutôt que par destinataire / par voyage** : un destinataire peut recevoir plusieurs colis distincts d'une même expédition ; pour l'expéditeur, chaque colis livré est une réussite indépendante. Plus simple et plus naturel à expliquer côté UI.
- **Flag d'idempotence sur `bids` (option β)** : redondant en théorie avec la transition irréversible `COMPLETED`, mais fournit une défense en profondeur contre les rejeux d'event Spring et garde la symétrie avec le voyageur (`announcements.total_trips_counted`). Coût négligeable (1 colonne booléenne, +1 save).
- **`AFTER_COMMIT` + `REQUIRES_NEW`** : identique au listener voyageur. Garantit qu'un rollback dans `confirmDelivery` n'incrémente pas, et qu'un échec de l'incrément ne casse pas la confirmation de livraison.
- **`Integer senderTotalShipments` (nullable)** dans `BidResponse` : si le sender a été soft-deleted, on retourne `null` plutôt que `0` pour ne pas mentir sur l'historique.
