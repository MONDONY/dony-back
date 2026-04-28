# Story 9.1 → 9.8 — Profils & Évaluations (Backend)

**Date:** 2026-04-26
**Status:** ✅ Complète

## Résumé

Implémentation complète du système de réputation dony : évaluations expéditeur→voyageur (authentifiées) et destinataire→voyageur (anonymes via token), badge Kilo Pro avec anti-farming, refus de colis par le voyageur, suspension automatique de l'expéditeur, pénalité no-show, détection de fraude sur les notes, et suppression de compte RGPD.

---

## Fichiers créés

### Package `ratings/`
- `RatingEntity.java` — entité JPA table `ratings` (raterId nullable pour destinataire sans compte, trackingToken, excludedFromAverage)
- `RatingRepository.java` — queries JPQL : farming (30j), notes incluses, notes récentes pour badge
- `RatingService.java` — logique création note expéditeur + destinataire + recalcul moyenne
- `RatingController.java` — `POST /ratings` (SENDER) + `POST /ratings/recipient` (public)
- `BadgeService.java` — attribution Kilo Pro avec anti-farming (phones distincts + délai 7j)
- `FraudDetectionService.java` — détection farming rating (>3 notes même pair / all-5★-no-comment)
- `DeliveryConfirmedEventListener.java` — écoute `DeliveryConfirmedEvent` → badge + fraud check
- `events/RatingCreatedEvent.java` — event publié après chaque note créée

### Package `matching/events/`
- `ParcelRefusedEvent.java` — publié quand un voyageur refuse un colis
- `VoyageurNoShowEvent.java` — publié quand le scheduler détecte un no-show

### Package `matching/`
- `NoShowScheduler.java` — `@Scheduled` horaire : détecte bids ACCEPTED sans scan départ après délai, marque NO_SHOW, incrémente noShowCount, alerte admin si noShowCount ≥ 2

### Package `auth/`
- `UserService.java` — `checkAndSuspendSender()`, `unsuspendUser()`, `deleteAccount()` (RGPD complet)
- `ParcelRefusedSuspensionListener.java` — écoute `ParcelRefusedEvent` → vérifie seuil suspension
- `events/UserSuspendedEvent.java` — publié quand un utilisateur est suspendu

### Package `payments/`
- `ParcelRefusedEventListener.java` — écoute `ParcelRefusedEvent` → remboursement Stripe escrow
- `NoShowEventListener.java` — écoute `VoyageurNoShowEvent` → remboursement Stripe escrow

### Package `admin/`
- `AdminUserController.java` — `POST /admin/users/{userId}/unsuspend` (ADMIN only)

### Migrations Flyway
- `V27__ratings_and_user_reputation.sql` — table `ratings`, colonnes reputation dans `users` (average_rating, kilo_pro, kilo_pro_granted_at, no_show_count, refused_count), colonnes refus dans `bids` (refusal_reason, refusal_photo_url, no_show_at)
- `V28__ratings_stars_int.sql` — correction type `stars` SMALLINT → INTEGER (compatibilité JPA)

### Tests
- `src/test/java/com/dony/api/ratings/RatingServiceTest.java` — 10 tests
- `src/test/java/com/dony/api/ratings/BadgeServiceTest.java` — 6 tests
- `src/test/java/com/dony/api/ratings/FraudDetectionServiceTest.java` — 5 tests
- `src/test/java/com/dony/api/auth/UserServiceTest.java` — 7 tests
- `src/test/java/com/dony/api/matching/NoShowSchedulerTest.java` — 3 tests

---

## Fichiers modifiés

- `matching/BidStatus.java` — ajout `NO_SHOW` et `PARCEL_REFUSED` dans l'enum
- `matching/BidEntity.java` — ajout `refusalReason`, `refusalPhotoUrl`, `noShowAt`
- `matching/BidRepository.java` — ajout `findNoShowBids()`, `findCompletedBidsByTravelerId()`, `findByAnnouncementIdAndStatusIn()`
- `matching/BidController.java` — ajout `POST /bids/{bidId}/refuse-parcel` (TRAVELER)
- `matching/BidService.java` — ajout `refuseParcel()` : validation ownership + publication events
- `auth/UserEntity.java` — ajout `averageRating`, `kiloPro`, `kiloProGrantedAt`, `noShowCount`, `refusedCount`
- `auth/AuthService.java` — `deleteAccount()` délègue entièrement à `UserService.deleteAccount()`
- `auth/AuthServiceTest.java` — remplacement des 3 anciens tests deleteAccount par 1 test de délégation
- `payments/PaymentRepository.java` — ajout `existsByBidIdInAndStatus()`
- `notifications/NotificationDispatcher.java` — ajout listeners `onParcelRefused`, `onVoyageurNoShow`, `onUserSuspended`
- `matching/AnnouncementService.java` — `TravelerProfileDto` utilise maintenant `averageRating` et `kiloPro` réels
- `tracking/RecipientController.java` — ajout attribut `isDelivered` au modèle Thymeleaf
- `templates/recipient/tracking.html` — ajout formulaire de notation (étoiles + commentaire) conditionnel si `isDelivered=true`

---

## Comment ça fonctionne

### Flux 1 : Expéditeur note le voyageur (Story 9.1)

1. Expéditeur `POST /api/v1/ratings` avec `bidId`, `stars`, `comment`
2. `RatingService.createRating()` :
   - Récupère l'expéditeur via `firebaseUid`
   - Vérifie que le bid appartient à l'expéditeur → 403 sinon
   - Vérifie `bid.status == COMPLETED` → 422 sinon
   - Vérifie fenêtre 7 jours depuis `bid.updatedAt` → 422 "rating-window-expired" sinon
   - Vérifie absence de doublon via `existsByBidIdAndRaterId` → 409 sinon
   - Résout `travelerId` via `AnnouncementRepository.findById(bid.announcementId)`
   - Sauvegarde `RatingEntity` avec `raterId = sender.id`
   - Appelle `recalculateAverageRating(travelerId)`
   - Publie `RatingCreatedEvent` (déclenche fraud check async)

### Flux 2 : Destinataire note via page web (Story 9.2)

1. Destinataire ouvre `GET /tracking/{token}` → page Thymeleaf avec formulaire si `isDelivered=true`
2. Formulaire soumet `POST /api/v1/ratings/recipient` (endpoint public, pas de token Firebase)
3. `RatingService.createRecipientRating()` :
   - Trouve le bid via `trackingToken`
   - Vérifie `bid.status == COMPLETED` → 422 sinon
   - Vérifie doublon via `existsByBidIdAndTrackingToken` → 409 sinon
   - Sauvegarde `RatingEntity` avec `raterId = null` et `trackingToken` renseigné
   - Recalcule la moyenne, publie `RatingCreatedEvent`

### Flux 3 : Attribution du badge Kilo Pro (Story 9.3)

Déclenché de manière asynchrone après chaque `DeliveryConfirmedEvent` :

1. `DeliveryConfirmedEventListener.onDeliveryConfirmed()` → `BadgeService.checkAndGrantKiloPro(travelerId)`
2. Critères vérifiés dans l'ordre :
   - Utilisateur non suspendu, pas déjà Kilo Pro
   - ≥ 5 livraisons COMPLETED comme voyageur
   - ≥ 5 destinataires différents (phones distincts sur `BidEntity.recipientPhone`) → sinon : `KILO_PRO_FARMING_DETECTED`
   - Délai minimum 7 jours entre chaque livraison → sinon : `KILO_PRO_FARMING_DETECTED`
   - Moyenne des 5 dernières notes ≥ 4.0 → sinon : pas de badge
3. Si tous les critères sont OK : `user.kiloPro = true`, `user.kiloProGrantedAt = now()`, log `KILO_PRO_GRANTED`

### Flux 4 : Refus de colis (Story 9.4)

1. Voyageur `POST /api/v1/bids/{bidId}/refuse-parcel` avec `reason`
2. `BidService.refuseParcel()` :
   - Vérifie que le voyageur est bien propriétaire de l'annonce (via `AnnouncementRepository`)
   - Vérifie `bid.status == ACCEPTED`
   - Met `bid.status = PARCEL_REFUSED`, sauvegarde `refusalReason` + `refusalPhotoUrl`
   - Incrémente `sender.refusedCount`
   - Publie `ParcelRefusedEvent`
   - Log audit `BID_PARCEL_REFUSED`
3. En parallèle (async via events) :
   - `ParcelRefusedSuspensionListener` → `UserService.checkAndSuspendSender()` → suspension si `refusedCount >= 2`
   - `ParcelRefusedEventListener` (payments) → remboursement Stripe escrow

### Flux 5 : Suspension automatique de l'expéditeur (Story 9.5)

1. `UserService.checkAndSuspendSender(senderId)` :
   - Charge l'utilisateur
   - Si `status == SUSPENDED` déjà : no-op
   - Si `refusedCount >= 2` : `user.status = SUSPENDED`, publie `UserSuspendedEvent`
2. `NotificationDispatcher.onUserSuspended()` → notification SMS/push à l'expéditeur
3. `FirebaseTokenFilter` bloque automatiquement les requêtes de l'utilisateur suspendu → 403

**Réactivation :** `POST /api/v1/admin/users/{userId}/unsuspend` (ADMIN) → `user.status = ACTIVE`, log `USER_UNSUSPENDED`

### Flux 6 : No-show voyageur (Story 9.6)

`NoShowScheduler.detectNoShows()` tourne toutes les heures :
1. `bidRepository.findNoShowBids(cutoff)` : bids ACCEPTED dont la date de passage est dépassée ET sans événement de scan `DEPART` en tracking_events
2. Pour chaque bid :
   - `bid.status = NO_SHOW`, `bid.noShowAt = now()`
   - `traveler.noShowCount++`
   - Publie `VoyageurNoShowEvent`
   - Si `noShowCount >= 2` : log audit `ADMIN_ALERT_RECURRING_NO_SHOW`
3. `NoShowEventListener` (payments) → remboursement Stripe escrow à l'expéditeur

### Flux 7 : Détection de fraude sur les notes (Story 9.7)

Déclenché async après chaque `RatingCreatedEvent` si `raterId != null` :
1. `FraudDetectionService.detectRatingFarming(ratingId)`
2. Charge toutes les notes du même couple (raterId, ratedUserId) dans les 30 derniers jours
3. Deux règles de détection :
   - Plus de 3 notes du même expéditeur → toutes exclues
   - Toutes les notes sont 5★ sans commentaire → toutes exclues
4. Si fraude : `rating.excludedFromAverage = true` sur toutes les notes suspectes, `ratingRepository.saveAll()`, log audit `FRAUD_ALERT_RATING_FARMING`

### Flux 8 : Suppression de compte RGPD (Story 9.8)

1. Utilisateur `DELETE /api/v1/auth/account` → `AuthService.deleteAccount()` délègue à `UserService.deleteAccount(firebaseUid)`
2. `UserService.deleteAccount()` :
   - Vérifie absence de paiement actif en `ESCROW` (via bids de l'utilisateur) → 422 si actif
   - Pseudonymise : `email = "deleted_{uuid}@dony.app"`, `phone = "+00000000000"`, `firstName = "Utilisateur"`, `lastName = "Supprimé"`
   - `user.status = BANNED`
   - Soft-delete des entrées KYC
   - Supprime les fichiers KYC sur Hetzner S3 via `StorageService.deleteFile()`
   - Révoque le compte Firebase via `FirebaseAuth.getInstance().deleteUser(uid)`
   - Log audit `USER_GDPR_DELETION`

---

## Points d'entrée API

| Méthode | Endpoint | Rôle requis | Description |
|---------|----------|-------------|-------------|
| `POST` | `/api/v1/ratings` | `ROLE_SENDER` | Expéditeur note le voyageur |
| `POST` | `/api/v1/ratings/recipient` | Public | Destinataire note le voyageur via trackingToken |
| `POST` | `/api/v1/bids/{bidId}/refuse-parcel` | `ROLE_TRAVELER` | Voyageur refuse un colis |
| `POST` | `/api/v1/admin/users/{userId}/unsuspend` | `ROLE_ADMIN` | Désuspendre un expéditeur |
| `DELETE` | `/api/v1/auth/account` | Authentifié | Suppression de compte RGPD |

---

## Events Spring publiés / écoutés

| Event | Publié par | Écouté par | Action |
|-------|-----------|-----------|--------|
| `RatingCreatedEvent` | `RatingService` | `DeliveryConfirmedEventListener` | Fraud check async |
| `ParcelRefusedEvent` | `BidService` | `ParcelRefusedSuspensionListener` (auth) | Vérifie suspension expéditeur |
| `ParcelRefusedEvent` | `BidService` | `ParcelRefusedEventListener` (payments) | Remboursement Stripe |
| `ParcelRefusedEvent` | `BidService` | `NotificationDispatcher` | Notification push/SMS |
| `VoyageurNoShowEvent` | `NoShowScheduler` | `NoShowEventListener` (payments) | Remboursement Stripe |
| `VoyageurNoShowEvent` | `NoShowScheduler` | `NotificationDispatcher` | Notification push/SMS |
| `UserSuspendedEvent` | `UserService` | `NotificationDispatcher` | Notification push/SMS |
| `DeliveryConfirmedEvent` | `TrackingService` | `DeliveryConfirmedEventListener` (ratings) | Badge Kilo Pro check |

---

## Entités JPA impliquées

- `RatingEntity` → table `ratings` — `raterId` nullable (null = destinataire sans compte), `excludedFromAverage` pour le fraud
- `UserEntity` → table `users` — nouveaux champs `averageRating`, `kiloPro`, `kiloProGrantedAt`, `noShowCount`, `refusedCount`
- `BidEntity` → table `bids` — nouveaux champs `refusalReason`, `refusalPhotoUrl`, `noShowAt`; nouveau statuts `NO_SHOW`, `PARCEL_REFUSED`

---

## Pièges et points d'attention

1. **`stars` doit être INTEGER, pas SMALLINT.** La migration V28 corrige le type. Si on ajoute un champ `int` JPA sans `columnDefinition`, Hibernate attend `INTEGER` — ne pas l'oublier pour les futures migrations.

2. **travelerId n'est pas sur BidEntity.** Il faut passer par `AnnouncementEntity` pour le résoudre : `bid.announcementId` → `announcement.travelerId`. Ne pas chercher un champ direct.

3. **fenêtre 7 jours calculée sur `bid.updatedAt`** (date de la dernière mise à jour = date de confirmation COMPLETED), pas sur `createdAt`.

4. **Le fraud check est asynchrone** (`@Async`). Les notes ne sont pas exclues immédiatement après la sauvegarde — elles le sont lors du prochain tick event. Ne pas s'attendre à un effet synchrone dans les tests.

5. **GDPR : vérifier l'ESCROW via une liste de bidIds**, pas via un champ senderId/travelerId sur PaymentEntity. `paymentRepository.existsByBidIdInAndStatus(bidIds, ESCROW)` — ne pas oublier de collecter les bids des deux rôles (SENDER et TRAVELER).

6. **Anti-farming Kilo Pro sur `recipientPhone`** du `BidEntity`. Ce champ doit être renseigné à la création du bid (Epic 3) pour que le contrôle fonctionne.

7. **`NoShowScheduler.findNoShowBids()`** utilise un NOT EXISTS sur `TrackingEventEntity` de type `DEPART`. Si le schéma de `tracking_events` change (enum type), cette query JPQL devra être mise à jour.

---

## Critères d'acceptation couverts

- [x] **9.1** — Expéditeur peut noter le voyageur après livraison confirmée ; erreurs 422/403/409 gérées
- [x] **9.2** — Destinataire peut noter via URL trackingToken sans compte ; formulaire Thymeleaf affiché
- [x] **9.3** — Badge Kilo Pro accordé si 5 livraisons distinctes espacées + moyenne ≥ 4 ; farming détecté et loggé
- [x] **9.4** — Voyageur peut refuser un colis avec raison ; bid passe PARCEL_REFUSED ; escrow remboursé
- [x] **9.5** — Expéditeur suspendu automatiquement après 2 refus ; désuspension admin disponible
- [x] **9.6** — No-show détecté horaire ; noShowCount incrémenté ; alerte admin si récidive ; escrow remboursé
- [x] **9.7** — Farming détecté (>3 notes pair/30j ou all-5★-no-comment) ; notes exclues de la moyenne
- [x] **9.8** — Suppression RGPD : pseudonymisation + KYC effacé + Firebase révoqué + refus si ESCROW actif

---

## Tests

- `./mvnw test` → **257 tests, 0 rouge, 0 erreur**
- Tests ajoutés :
  - `RatingServiceTest` — 10 tests (création valide, bid non livré, fenêtre expirée, doublon, wrong sender, token invalide)
  - `BadgeServiceTest` — 6 tests (critères remplis, pas assez de livraisons, farming destinataire, farming dates, note faible, déjà Kilo Pro, suspendu)
  - `FraudDetectionServiceTest` — 5 tests (farming pair, all-5★-no-comment, normal, raterId null, ratingId inconnu)
  - `UserServiceTest` — 7 tests (suspension, seuil non atteint, déjà suspendu, RGPD sans transaction, RGPD avec ESCROW, inconnu, désuspension)
  - `NoShowSchedulerTest` — 3 tests (no-show détecté, récidive alerte admin, aucun bid)

---

## Décisions techniques

**Pourquoi `raterId` nullable dans `RatingEntity` ?**
Le destinataire n'a pas de compte dony. Sa note est identifiée par `trackingToken` uniquement. Le `raterId` null est le signal que la note vient d'un destinataire anonyme — c'est aussi pourquoi le fraud check saute les notes avec `raterId = null`.

**Pourquoi V28 séparé pour `stars` ?**
La migration V27 a créé `stars SMALLINT`. Hibernate mappe `int` Java sur `INTEGER` PostgreSQL et rejette le mismatch au démarrage. Plutôt que de modifier V27 (interdit par les règles Flyway), une migration corrective V28 est créée.

**Pourquoi `UserService` séparé d'`AuthService` ?**
`AuthService` gérait déjà la logique d'inscription/connexion. Y ajouter la suspension et le RGPD l'aurait rendu trop large. `UserService` regroupe toutes les mutations de cycle de vie utilisateur, et `AuthService.deleteAccount()` délègue simplement — pas de duplication.

**Pourquoi la vérification GDPR passe par les bidIds ?**
`PaymentEntity` n'a pas de champ `senderId`/`travelerId` direct — seulement `bidId`. Il faut donc d'abord récupérer tous les bids de l'utilisateur (dans les deux rôles), puis vérifier s'il existe un paiement en `ESCROW` pour l'un d'eux.
