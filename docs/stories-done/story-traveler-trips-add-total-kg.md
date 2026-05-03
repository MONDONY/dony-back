# Story Traveler Trips — Ajout `total_kg` aux annonces (Backend)

**Date:** 2026-05-03
**Status:** ✅ Complète

## Résumé
Ajout d'une colonne `total_kg` sur `announcements` représentant la capacité totale initiale du trajet (figée à la création), permettant à l'app de calculer le remplissage `booked = total_kg - available_kg` et d'afficher une barre de progression sur la liste "Mes trajets" du voyageur.

## Fichiers créés
- `src/main/resources/db/migration/V41__add_total_kg_to_announcements.sql` — migration Flyway : ajoute la colonne, backfill via `available_kg + sum(weight_kg)` des bids dont le poids a été déduit (`ACCEPTED`, `COMPLETED`, `NO_SHOW`, `PARCEL_REFUSED`), puis `SET NOT NULL`.

## Fichiers modifiés
- `src/main/java/com/dony/api/matching/AnnouncementEntity.java` — champ `totalKg` + getter/setter, mappé sur `total_kg NOT NULL precision 5 scale 2`.
- `src/main/java/com/dony/api/matching/AnnouncementService.java` :
  - `createAnnouncement` → set `totalKg = availableKg`.
  - `updateAnnouncement` → set `totalKg = availableKg` (l'update est déjà rejeté si des bids ACCEPTED existent, donc il n'y a pas de poids réservé à préserver).
  - `toResponse`, `toSearchResponse`, `getAnnouncementDetail`, `updateAnnouncement` → propagent `totalKg` dans les DTOs.
- `src/main/java/com/dony/api/matching/dto/AnnouncementResponse.java` — ajout du champ `totalKg`.
- `src/main/java/com/dony/api/matching/dto/AnnouncementDetailResponse.java` — idem.
- `src/main/java/com/dony/api/matching/dto/AnnouncementSearchResponse.java` — idem.
- 7 tests (entité créée puis assertée) : ajout de `setTotalKg(...)` après chaque `setAvailableKg(...)`.
- `AnnouncementServiceTest` : 2 nouveaux tests.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble
- À la **création** d'une annonce, `available_kg` et `total_kg` sont initialisés à la même valeur (capacité demandée par le voyageur).
- Quand un bid passe à `ACCEPTED`, `BidService` décrémente `available_kg` du poids du bid (logique existante, inchangée). `total_kg` reste figé.
- Quand un bid `ACCEPTED` est annulé, `BidService` ré-incrémente `available_kg`. `total_kg` reste figé.
- Quand le voyageur **modifie** son trajet (route `PUT /announcements/{id}`), l'API rejette déjà la modification s'il y a un bid `ACCEPTED` (`modification-impossible`). Donc à ce stade `available_kg = total_kg` et synchroniser les deux est sûr.

### Backfill migration V41
Pour chaque annonce existante :
```sql
total_kg = available_kg
         + COALESCE(SUM(bids.weight_kg WHERE status IN
              ('ACCEPTED','COMPLETED','NO_SHOW','PARCEL_REFUSED')), 0)
```
Ces statuts correspondent aux cas où `BidService` a déduit le poids et ne l'a pas restitué. Pour les bids `PENDING`/`AWAITING_PAYMENT` le poids n'a jamais été déduit ; pour `REJECTED`/`CANCELLED` (post-acceptation) le poids a été restitué.

### Points d'attention
- **`total_kg NOT NULL`** : tout test qui persiste un `AnnouncementEntity` directement (sans passer par `AnnouncementService.createAnnouncement`) doit appeler `setTotalKg(...)` — sinon Hibernate lève une `ConstraintViolationException`.
- **Précision** : `precision = 5, scale = 2` — même format que `available_kg`. Max 999.99 kg, suffisant pour le métier.
- **Pas de modification possible si bids ACCEPTED** : c'est la raison pour laquelle on peut sereinement synchroniser `total_kg = available_kg` à l'update sans recalculer le booked. Si cette règle change un jour, il faudra revoir la logique d'update.

## Critères d'acceptation
- [x] Capacité totale exposée par l'API pour permettre l'affichage de la barre de remplissage côté Flutter.
- [x] Migration backfill correcte pour les annonces existantes.
- [x] Logique de création/mise à jour cohérente.

## Tests
- `./mvnw test` → 423 tests passent, 0 failures, 0 errors.
- Nouveaux tests :
  - `AnnouncementServiceTest.CreateTests#create_setsTotalKgEqualToAvailableKg`
  - `AnnouncementServiceTest.UpdateTests#update_setsTotalKgEqualToAvailableKg`
- Tests adaptés (ajout de `setTotalKg` dans les fixtures persistées) : `BidVisibilityTest`, `BidCheckoutServiceTest`, `AnnouncementServiceTest`, `CancellationServiceTest`, `TravelerStatsListenerIT`, `BidServiceTest`, `AnnouncementControllerIntegrationTest`.

## Décisions techniques
- **`total_kg` figé vs recalculé** : choix de figer à la création plutôt que stocker uniquement la somme des bids et calculer côté front. Avantage : une seule source de vérité, pas de calcul dépendant de l'ordre des événements (acceptation/annulation/refund). Inconvénient mineur : si un voyageur souhaite augmenter sa capacité après création, on resynchronise les deux champs (acceptable car bloqué dès qu'il y a des bids acceptés).
- **Backfill via somme des poids** plutôt que `available_kg` brut : plus précis pour les annonces ayant déjà des bids consommés. Si on s'était contenté de `total_kg = available_kg`, les annonces remplies à moitié auraient affiché une barre "0% remplie" après migration.
