# Design — Suppression de compte utilisateur (RGPD)

**Date :** 2026-05-06
**Statut :** Approuvé — prêt pour implémentation

---

## Contexte

L'endpoint `DELETE /auth/me` existe déjà dans `AuthController` mais son implémentation dans `UserService.deleteAccount()` présente trois problèmes :

1. **Violation d'architecture** — `UserService` injecte directement `BidRepository` et `PaymentRepository` (cross-package interdit, doit passer par Spring Events).
2. **Bug bids voyageur** — seuls les bids *complétés* du voyageur sont vérifiés pour le check ESCROW ; ses bids actifs (ACCEPTED) ne bloquent pas la suppression.
3. **Soft-delete manquant** — `user.softDelete()` (`deleted_at`) n'est jamais appelé ; seul le statut `BANNED` est set.
4. **Aucun test** pour ce flux.

---

## Décisions validées

| Question | Décision |
|---|---|
| Période de grâce | 30 jours (`PENDING_DELETION`) |
| Annonces/bids pendant la grâce | Archivés/annulés immédiatement |
| Réactivation dans les 30 jours | Compte `ACTIVE`, annonces/bids restent archivés |
| Notifications | Aucune |
| Blocker | Paiement `ESCROW` actif → `422` |

---

## State Machine

```
ACTIVE ──────────────────────────────► PENDING_DELETION
                DELETE /auth/me                │
                                               │ J+30 (scheduler)
ACTIVE ◄──────────────────────────────         ▼
           POST /auth/me/reactivate       BANNED + soft-deleted
                                         (pseudonymisation RGPD)
```

`FirebaseTokenFilter` : `PENDING_DELETION` laisse passer les requêtes (l'utilisateur peut se rétracter). Seuls `SUSPENDED` et `BANNED` bloquent.

---

## Modèle de données

### `UserStatus` (enum Java)
```java
ACTIVE, SUSPENDED, BANNED, PENDING_DELETION
```

### `UserEntity` — champ ajouté
```java
@Column(name = "deletion_requested_at")
private Instant deletionRequestedAt;
```

### Migration Flyway V9
```sql
ALTER TABLE users ADD COLUMN deletion_requested_at TIMESTAMPTZ;
-- PENDING_DELETION est géré côté Java (VARCHAR, pas de type ENUM PostgreSQL)
```

---

## API

### `DELETE /auth/me` — modifié (existant)

**Préconditions :**
- Utilisateur authentifié (token Firebase valide)
- Aucun paiement `ESCROW` actif (check via `PaymentRepository` — seul compromis acceptable pour éviter un event synchrone artificiel)

**Comportement :**
- Si déjà `PENDING_DELETION` → idempotent, retourne `204` sans ré-publier l'event
- Sinon → `status = PENDING_DELETION`, `deletionRequestedAt = Instant.now()`
- Publie `AccountDeletionRequestedEvent(userId)`
- Retourne `204 No Content`

**Erreurs :**
- `422 active-transactions` — paiement ESCROW actif
- `404 user-not-found` — utilisateur introuvable

---

### `POST /auth/me/reactivate` — nouveau

**Préconditions :**
- Utilisateur authentifié
- Statut = `PENDING_DELETION` (sinon `409`)

**Comportement :**
- `status = ACTIVE`, `deletionRequestedAt = null`
- Entrée audit_log : `USER_DELETION_CANCELLED`
- Retourne `200 UserResponse`

**Erreurs :**
- `409 not-pending-deletion` — compte non en cours de suppression

---

## Spring Events

### Event publié (package `auth/`)

```java
// auth/events/AccountDeletionRequestedEvent.java
public record AccountDeletionRequestedEvent(UUID userId) {}
```

### Listener (package `matching/`)

```java
// matching/AccountDeletionListener.java
@EventListener
@Transactional
public void onDeletionRequested(AccountDeletionRequestedEvent event) {
    announcementRepository.archiveByUserId(event.userId());   // status → ARCHIVED
    bidRepository.cancelOpenBidsByUserId(event.userId());     // bids PENDING/ACCEPTED → CANCELLED
}
```

Cela corrige le bug existant : tous les bids ouverts (sender ET traveler, pas seulement les complétés) sont annulés, sans injection cross-package.

---

## Scheduler

```java
// auth/AccountDeletionScheduler.java
@Scheduled(cron = "0 0 2 * * *")   // chaque nuit à 2h UTC
@Transactional
public void finalizeExpiredDeletions() {
    Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
    List<UserEntity> toDelete = userRepository
        .findByStatusAndDeletionRequestedAtBefore(PENDING_DELETION, cutoff);
    toDelete.forEach(userService::finalizeGdprDeletion);
}
```

### `UserService.finalizeGdprDeletion(UserEntity user)`

Extrait de la logique actuelle de `deleteAccount()` :
1. Pseudonymise les données personnelles (email, téléphone, nom, prénom, ville, date de naissance, fcmToken)
2. `user.setStatus(BANNED)`
3. `user.softDelete()` — set `deleted_at = now()` (**correction du bug actuel**)
4. Soft-delete du record KYC associé
5. Révocation du compte Firebase (`FirebaseAuth.getInstance().deleteUser(firebaseUid)`)
6. Entrée audit_log : `USER_GDPR_DELETION`

---

## Fichiers à créer / modifier

### Créer
- `auth/events/AccountDeletionRequestedEvent.java`
- `auth/AccountDeletionScheduler.java`
- `matching/AccountDeletionListener.java`
- `db/migration/V9__add_deletion_requested_at.sql`

### Modifier
- `auth/UserStatus.java` — ajouter `PENDING_DELETION`
- `auth/UserEntity.java` — ajouter champ `deletionRequestedAt` + getter/setter
- `auth/UserRepository.java` — ajouter `findByStatusAndDeletionRequestedAtBefore()`
- `auth/UserService.java` — refactoriser `deleteAccount()` en deux méthodes : `requestDeletion()` + `finalizeGdprDeletion()` + `reactivateAccount()`
- `auth/AuthService.java` — ajouter `reactivateAccount()`
- `auth/AuthController.java` — ajouter `POST /auth/me/reactivate`
- `matching/BidRepository.java` — ajouter `cancelOpenBidsByUserId()`
- `matching/AnnouncementRepository.java` — ajouter `archiveByUserId()`

### Tests à créer
- `auth/UserServiceDeleteAccountTest.java`
- `auth/AccountDeletionSchedulerTest.java`
- `matching/AccountDeletionListenerTest.java`
- `auth/AuthControllerDeleteAccountTest.java`

---

## Cas de test

### `UserServiceDeleteAccountTest`
- Demande avec ESCROW actif → `422`
- Demande réussie → statut `PENDING_DELETION`, `deletionRequestedAt` set, event publié
- Demande idempotente (déjà `PENDING_DELETION`) → `204`, event non re-publié
- Réactivation depuis `PENDING_DELETION` → statut `ACTIVE`, `deletionRequestedAt` null
- Réactivation depuis `ACTIVE` → `409`
- `finalizeGdprDeletion` → pseudonymisation + `deleted_at` set + Firebase révoqué

### `AccountDeletionSchedulerTest`
- Seuls les users avec `deletionRequestedAt < now - 30j` sont finalisés
- Users `PENDING_DELETION` récents non touchés (idempotence)

### `AccountDeletionListenerTest`
- Event reçu → annonces archivées + bids ouverts annulés

### `AuthControllerDeleteAccountTest`
- `DELETE /auth/me` → `204`
- `DELETE /auth/me` avec ESCROW → `422`
- `POST /auth/me/reactivate` → `200` + `UserResponse`
- `POST /auth/me/reactivate` sur compte `ACTIVE` → `409`
