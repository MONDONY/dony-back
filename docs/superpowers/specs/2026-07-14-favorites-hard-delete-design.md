# Favoris — passage au hard delete

**Date :** 2026-07-14
**Statut :** validé par le user

## Objectif

Quand un utilisateur retire un favori (trajet ou demande d'envoi), la ligne est supprimée
physiquement de la table `favorites` — plus de soft delete. Idem pour le nettoyage
automatique des favoris dont la cible a atteint un état terminal.

## Contexte

Implémentation actuelle (mergée via PR back #84) :

- `FavoriteService.removeFavorite` → `softDelete()` sur la ligne active.
- `FavoriteService.addFavorite` → réactive (`revive()`) une ligne soft-deleted via
  `findIncludingDeleted`, sinon insert.
- `FavoriteCleanupScheduler` (cron minuit UTC) → soft-delete des favoris dont la cible
  est COMPLETED/CANCELLED (TRIP) ou COMPLETED/CANCELLED/EXPIRED (PACKAGE_REQUEST).
- Index unique partiel `ux_favorites_active (user_id, target_type, target_id) WHERE deleted_at IS NULL`.

## Décision

Hard delete partout. Un favori est une donnée de préférence pure, sans valeur
historique, comptable ou de litige : exception assumée à la règle projet
« soft delete only ». Pas d'entrée `audit_log` (inchangé — action non significative).

## Changements

### 1. `FavoriteService`

- `removeFavorite` : `favoriteRepository.delete(fav)` (DELETE physique). No-op si absent.
- `addFavorite` : suppression de la branche `revive()`. Devient : si
  `existsByUserIdAndTargetTypeAndTargetId` → no-op, sinon `save`. Une
  `DataIntegrityViolationException` (course entre deux requêtes simultanées, index
  unique) est attrapée et traitée comme un no-op — l'opération reste idempotente.

### 2. `FavoriteEntity`

- Suppression de `revive()`.
- `@Where(clause = "deleted_at IS NULL")` et la colonne `deleted_at` (héritée de
  `BaseEntity`) restent : toujours NULL désormais, inoffensifs.

### 3. `FavoriteRepository`

- Suppression de `findIncludingDeleted`.
- `softDeleteTripFavoritesForTerminalAnnouncements` →
  `deleteTripFavoritesForTerminalAnnouncements` : `DELETE FROM favorites WHERE ...`
  (mêmes conditions de statut cible).
- `softDeletePackageRequestFavoritesForTerminalRequests` →
  `deletePackageRequestFavoritesForTerminalRequests` : idem.

### 4. Migration `V172__favorites_purge_soft_deleted.sql`

```sql
DELETE FROM favorites WHERE deleted_at IS NOT NULL;
```

Purge one-shot de l'historique soft-deleted. Index existants inchangés (les index
partiels `WHERE deleted_at IS NULL` restent valides — la condition est désormais
toujours vraie).

### 5. Tests

- Mise à jour des tests unitaires `FavoriteService` : remove → `delete()` vérifié,
  add → plus de revive, cas course (`DataIntegrityViolationException` → no-op).
- Mise à jour des tests du scheduler et du repository (DELETE au lieu d'UPDATE).
- `./mvnw test` vert, couverture JaCoCo ≥ 90 %.

## Hors scope

- **Frontend (yadony_app)** : aucun changement — contrat API identique (mêmes endpoints,
  toggle idempotent).
- Structure des index / colonne `deleted_at` : conservées.

## Critères d'acceptation

- Given un favori actif, when l'utilisateur le retire, then la ligne est absente de la
  table `favorites` (pas seulement `deleted_at` renseigné).
- Given un favori retiré puis remis, when on ajoute à nouveau, then une nouvelle ligne
  est insérée (pas de réactivation).
- Given un trajet favori passant à CANCELLED/COMPLETED, when le cron minuit tourne,
  then la ligne favorite est physiquement supprimée.
- Given deux requêtes d'ajout simultanées sur la même cible, when elles s'exécutent,
  then une seule ligne existe et aucune erreur n'est renvoyée au client.
- Given des lignes soft-deleted préexistantes, when V172 s'applique, then elles sont purgées.
