# Story B6 — Abonnement à un voyageur (Backend)

**Date:** 2026-05-25
**Status:** ✅ Complète

## Résumé
Refonte de la feature « récurrence / re-booking » en un concept unique d'**abonnement à un voyageur** : un expéditeur s'abonne explicitement à un voyageur, reçoit une notification in-app à chaque publication d'annonce (push opt-in par voyageur), consulte ses trajets et la liste de ses abonnements. Les features Favoris et rebooking 1-tap / past-bookings sont supprimées ; leurs données favoris sont migrées en abonnements.

## Fichiers créés
- `src/main/resources/db/migration/V104__traveler_subscriptions_push_and_new.sql` — ajoute `push_enabled` + `has_new`.
- `src/main/resources/db/migration/V105__merge_favorites_into_subscriptions.sql` — migre les favoris en abonnements puis `DROP TABLE favorite_travelers`.
- `src/main/java/com/dony/api/subscriptions/dto/SubscriptionStatusResponse.java` — état d'abonnement `(subscribed, pushEnabled)`.
- `src/main/java/com/dony/api/subscriptions/dto/SubscriptionItemResponse.java` — item enrichi de la liste « Mes abonnements » (+ `LastAnnouncement` imbriqué).
- `src/main/java/com/dony/api/matching/dto/TravelerAnnouncementResponse.java` — trajet public d'un voyageur.
- Tests : `subscriptions/SubscriptionServiceTest`, `subscriptions/SubscriptionControllerTest`, `subscriptions/TravelerSubscriptionRepositoryTest`, `matching/TravelerAnnouncementsControllerTest`, `notifications/NotificationDispatcherTest` (2 cas ajoutés), `subscriptions/TravelerAvailabilityListenerTest` (réécrit).

## Fichiers modifiés
- Package `com.dony.api.rebooking` **renommé** `com.dony.api.subscriptions`.
- `subscriptions/TravelerSubscriptionEntity.java` — champs `pushEnabled`, `hasNew`.
- `subscriptions/TravelerSubscriptionRepository.java` — `findBySenderIdAndTravelerId`, `findBySenderIdAndTravelerIdIncludingDeleted` (réactivation soft-delete), `findAllByTravelerId`, `findEnrichedBySenderId` (projection native).
- `subscriptions/SubscriptionService.java` (ex-`RebookingService`) — subscribe/unsubscribe/setPush/markSeen/getStatus/getMySubscriptions ; purge de getPastBookings/rebook.
- `subscriptions/SubscriptionController.java` (ex-`RebookingController`) — 6 endpoints ; purge past-bookings/rebook.
- `subscriptions/TravelerAvailabilityListener.java` — notif in-app toujours + push conditionnel + `has_new`.
- `notifications/NotificationDispatcher.java` — surcharge `notifyUser(..., boolean push)` (in-app sans push).
- `matching/TravelerStatsController.java` — endpoint public `GET /travelers/{id}/announcements`.
- `matching/AnnouncementService.java` — `getTravelerAnnouncements(UUID)`.
- `matching/AnnouncementRepository.java`, `matching/BidRepository.java` — retrait des requêtes rebooking mortes.
- `config/SecurityConfig.java` — `/travelers/*/announcements` public.
- **Supprimés** : package `addressbook/favorite/` (entité, repo, service, controller, DTOs) + ses tests ; `PastBookingResponse`, `RebookResponse`, `RebookingServiceTest`, `RebookingControllerTest`.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. **S'abonner** : `POST /travelers/{id}/subscribe` → `SubscriptionService.subscribe` crée (ou réactive si soft-deleted) une ligne `traveler_subscriptions` avec `push_enabled=false`. Idempotent.
2. **Cloche push** : `PUT /travelers/{id}/subscribe/push {enabled}` → met à jour `push_enabled`.
3. **Publication d'annonce** : `AnnouncementService` publie `AnnouncementPublishedEvent` ; `TravelerAvailabilityListener` (AFTER_COMMIT) parcourt les abonnés du voyageur, passe `has_new=true`, crée une notif in-app pour tous, et envoie le push FCM uniquement si `push_enabled`.
4. **Liste « Mes abonnements »** : `GET /me/subscriptions` → `findEnrichedBySenderId` (projection native JOIN users + sous-requêtes announcements) → nom, note, nb trajets en cours, dernière annonce, `pushEnabled`, `hasNew`.
5. **Consultation profil** : `GET /travelers/{id}/announcements` (public) liste les trajets ACTIVE/FULL ; `POST /me/subscriptions/{id}/mark-seen` remet `has_new=false`.
6. **Réserver** : flux de bid inchangé (`POST /announcements/{id}/bids`).

### Points d'entrée API
- `POST /travelers/{travelerId}/subscribe` (SENDER) — s'abonner, 201.
- `DELETE /travelers/{travelerId}/subscribe` (SENDER) — se désabonner (soft delete), 204.
- `PUT /travelers/{travelerId}/subscribe/push` (SENDER) — cloche on/off, renvoie le statut.
- `GET /travelers/{travelerId}/subscription` (SENDER) — `{subscribed, pushEnabled}`.
- `GET /me/subscriptions` (SENDER) — liste enrichie.
- `POST /me/subscriptions/{travelerId}/mark-seen` (SENDER) — remet `has_new=false`, 204.
- `GET /travelers/{travelerId}/announcements` (public) — trajets ACTIVE/FULL.

### Entités JPA impliquées
- `TravelerSubscriptionEntity` → table `traveler_subscriptions` (sender_id, traveler_id, push_enabled, has_new, soft delete `@Where deleted_at IS NULL`, UNIQUE(sender_id, traveler_id)).

### Logique métier critique
- **Idempotence + réactivation** : `subscribe` utilise `findBySenderIdAndTravelerIdIncludingDeleted` (requête native qui contourne `@Where`) pour réactiver un abonnement soft-deleted au lieu d'en créer un doublon (la contrainte UNIQUE l'interdirait).
- **In-app vs push** : la notif in-app est toujours persistée ; le push dépend de `push_enabled` par abonnement. `NotificationDispatcher.notifyUser(...)` 4-args délègue avec `push=true` (comportement inchangé pour tous les autres appelants).

### Events Spring publiés / écoutés
- `AnnouncementPublishedEvent` (publié par `matching`) → écouté par `TravelerAvailabilityListener` (`@TransactionalEventListener(AFTER_COMMIT)` + `@Async` + `@Transactional(propagation = REQUIRES_NEW)`).

### Pièges et points d'attention
- **`@Transactional(propagation = REQUIRES_NEW)` obligatoire** sur le listener AFTER_COMMIT — sans lui, Spring 6.x refuse de démarrer le contexte.
- **Projection native sans `LATERAL`** (sous-requêtes scalaires corrélées) pour rester compatible H2 (MODE=PostgreSQL) utilisé par `@DataJpaTest`. Ordre des 12 colonnes mappé par index dans `SubscriptionService.mapRow` — ne pas réordonner.
- **Flyway désactivé en test** (`flyway.enabled: false`, schéma via `ddl-auto`) : V104/V105 ne sont pas exécutées par les tests, mais au démarrage dev/prod sur PostgreSQL.
- **`gen_random_uuid()`** dans V105 : natif PostgreSQL 13+.

## Critères d'acceptation couverts
- [x] Abonnement explicite (subscribe) + idempotent / réactivation.
- [x] Désabonnement (soft delete).
- [x] Cloche push par voyageur, défaut OFF ; in-app toujours.
- [x] Notification à la publication (in-app + push conditionnel) + tap → annonce (`data.announcementId`).
- [x] Liste enrichie des abonnements + marqueur `has_new`.
- [x] Annonces publiques d'un voyageur.
- [x] Suppression Favoris + migration des données.

## Tests
- `./mvnw test` → **1173 tests, 0 échec, 0 erreur, 6 skipped** (BUILD SUCCESS).
- Couverture JaCoCo : package `com.dony.api.subscriptions` **99 %** (98/99 lignes), DTO **100 %**. Couverture globale du projet 77,5 % (état préexistant du legacy, hors scope de cette story).
- Classes de test ajoutées/réécrites : voir « Fichiers créés ».

## Décisions techniques
- **Réutiliser `traveler_subscriptions`** (et non `favorite_travelers` ni une table neuve) car la mécanique de notification y était déjà branchée → moindre risque. Favoris migrés puis table supprimée.
- **Endpoint de statut dédié** (`GET /travelers/{id}/subscription`) plutôt que d'enrichir le DTO profil public partagé → évite le couplage inter-package.
- **Projection native** pour `/me/subscriptions` (lecture cross-table users + announcements) → évite l'injection de service cross-package (interdite) et le N+1.
