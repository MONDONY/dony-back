# Bid Checkout — Payment-First Reservation Flow

**Date:** 2026-05-03
**Status:** Spec validée, en attente d'implémentation
**Auteur:** Claude (brainstorming guidé)

---

## Contexte & Problème

Aujourd'hui, dans `BidService.createBid` :

1. L'expéditeur appelle `POST /announcements/{id}/bids` → un `BidEntity` est inséré en base avec `status = PENDING`.
2. Un `BidCreatedEvent` est immédiatement publié → le voyageur reçoit une notification FCM/SMS.
3. Le paiement Stripe n'arrive **qu'après** que le voyageur ait accepté.

Conséquences indésirables :

- Le voyageur est sollicité par des demandes potentiellement non sérieuses (l'expéditeur n'a engagé aucun argent).
- Une demande abandonnée par l'expéditeur laisse une ligne en BDD et a déjà notifié le voyageur.
- Pas d'engagement financier au moment de la sollicitation → friction côté voyageur (qualité des leads).

**Objectif** : la notification voyageur ne doit partir **qu'après confirmation du paiement** par Stripe. Avant paiement réussi, la trace en BDD doit être éphémère et nettoyée automatiquement.

---

## Décisions de design

| # | Question | Choix | Justification |
|---|----------|-------|---------------|
| 1 | Quand l'expéditeur paie ? | **Avant** que le voyageur voie la demande (modèle BlaBlaCar/Uber) | Engagement financier à la création → meilleure qualité de leads |
| 2 | Capture immédiate ou pré-autorisation ? | **Pré-autorisation** (`capture_method=manual`, capture seulement à `acceptBid`) | 0 € de frais Stripe sur les refus voyageur (vs ~0.70 €/refus en capture immédiate). Hold expire à 7j max — compatible avec le timeout voyageur de 24h |
| 3 | Délai max de réponse voyageur | `min(24h après création du bid, departureDate - 12h)` | Couvre réservation tardive ET très en avance |
| 4 | Trace en BDD avant paiement | **Oui**, `status = AWAITING_PAYMENT`, **suppression physique** par scheduler 5 minutes après expiration (15 min après création) | Demandé explicitement par l'utilisateur. Ces bids n'ont jamais d'existence légale (jamais notifiés, jamais audités) → exception au "soft delete only" du CLAUDE.md, justifiée |
| 5 | Visibilité côté voyageur | `AWAITING_PAYMENT` invisible dans toutes les listes voyageur | Cohérent avec l'objectif : aucune sollicitation tant que non payé |
| 6 | Visibilité côté expéditeur | `AWAITING_PAYMENT` visible dans "Mes demandes" | Pour permettre la reprise du paiement si interruption |

---

## Vue d'ensemble du flux cible

```
Expéditeur                Backend                    Stripe                Voyageur
    │                       │                          │                      │
    │ POST /bids/checkout   │                          │                      │
    ├──────────────────────►│ Validations métier       │                      │
    │                       │ INSERT bid               │                      │
    │                       │ (AWAITING_PAYMENT,       │                      │
    │                       │  expires_at = now+15min) │                      │
    │                       │                          │                      │
    │                       │ PaymentIntent.create     │                      │
    │                       │ capture_method=MANUAL    │                      │
    │                       ├─────────────────────────►│                      │
    │                       │ client_secret            │                      │
    │                       │◄─────────────────────────┤                      │
    │ {bidId, clientSecret} │                          │                      │
    │◄──────────────────────┤                          │                      │
    │                       │                          │                      │
    │ Stripe PaymentSheet (carte + 3DS)                │                      │
    ├──────────────────────────────────────────────────►                      │
    │                       │                          │                      │
    │                       │ Webhook                  │                      │
    │                       │ payment_intent.          │                      │
    │                       │ amount_capturable_       │                      │
    │                       │ updated                  │                      │
    │                       │◄─────────────────────────┤                      │
    │                       │ bid.status = PENDING     │                      │
    │                       │ audit_log BID_CREATED    │                      │
    │                       │ publish BidCreatedEvent  │                      │
    │                       │      FCM + SMS                                  │
    │                       ├────────────────────────────────────────────────►│
    │                       │                          │                      │
    │                       │            (voyageur accepte)                   │
    │                       │◄────────────────────────────────────────────────┤
    │                       │ paymentIntent.capture()  │                      │
    │                       ├─────────────────────────►│                      │
    │                       │ → débit réel + escrow                           │
```

**Cas alternatifs** :

- Expéditeur abandonne le checkout → scheduler supprime le bid + cancel le PaymentIntent à T+15min (0 frais).
- Voyageur refuse → `paymentIntent.cancel()` → hold libéré (0 frais).
- Voyageur ne répond pas dans `min(24h, departure-12h)` → scheduler timeout : bid CANCELLED + `paymentIntent.cancel()`.
- Race condition (scheduler vs webhook au même instant) : si Stripe répond `payment_intent_unexpected_state` au cancel et le PI est `succeeded`, on promeut le bid en `PENDING` (rattrapage).

---

## Modèle de données

### Nouvelle valeur `BidStatus`

```java
public enum BidStatus {
    AWAITING_PAYMENT,  // ← NOUVEAU : Stripe hold initié, paiement non encore confirmé
    PENDING,           // (inchangé) paiement confirmé, voyageur notifié
    ACCEPTED,
    REJECTED,
    CANCELLED,
    COMPLETED,
    PARCEL_REFUSED
}
```

### Migration `V37__bids_add_payment_intent.sql`

```sql
ALTER TABLE bids
  ADD COLUMN payment_intent_id           VARCHAR(255),
  ADD COLUMN awaiting_payment_expires_at TIMESTAMP;

CREATE INDEX idx_bids_awaiting_payment
  ON bids (status, awaiting_payment_expires_at)
  WHERE status = 'AWAITING_PAYMENT';

CREATE INDEX idx_bids_payment_intent
  ON bids (payment_intent_id);
```

### Filtre visibilité (modifications `BidRepository`)

| Méthode | Comportement |
|---------|--------------|
| `findByAnnouncementId` (vue voyageur) | Exclure `AWAITING_PAYMENT` |
| `findBySenderId` (vue expéditeur) | Inclure `AWAITING_PAYMENT` |
| `existsBySenderIdAndAnnouncementIdAndStatusIn(...)` (anti-doublon) | Ajouter `AWAITING_PAYMENT` à la liste |
| `findByStatusAndAwaitingPaymentExpiresAtBefore` (NOUVEAU) | Pour le scheduler de cleanup |
| `findPendingTimedOut(LocalDateTime now)` (NOUVEAU) | `status = PENDING AND min(createdAt+24h, announcement.departureDate-12h) < now` |

---

## API

### `POST /api/v1/bids/checkout` (NOUVEAU — remplace `POST /announcements/{id}/bids`)

**Auth** : Bearer Firebase token, rôle `SENDER` (auto-attribué si absent).

**Request body** :
```json
{
  "announcementId": "uuid",
  "weightKg": "2.5",
  "declaredValueEur": "150.00",
  "description": "Médicaments",
  "contentCategory": "OTHER",
  "recipientName": "Aïssatou Diop",
  "recipientPhone": "+221771234567",
  "disclaimerSigned": true
}
```

**Réponse 201** :
```json
{
  "bidId": "uuid",
  "clientSecret": "pi_xxx_secret_yyy",
  "publishableKey": "pk_test_...",
  "expiresAt": "2026-05-03T14:15:00Z"
}
```

**Erreurs** (RFC 7807 ProblemDetail) — identiques à l'ancien `createBid` :
- 404 announcement not found
- 409 announcement-not-active, cannot-bid-own-announcement, already-bid
- 422 weight-exceeds-capacity, value-exceeds-limit, disclaimer-not-signed

### Webhook Stripe — `POST /api/v1/payments/webhook`

Doit traiter (en plus des événements déjà gérés) :

- `payment_intent.amount_capturable_updated` → promotion `AWAITING_PAYMENT` → `PENDING`, audit log, `BidCreatedEvent` publié.
- `payment_intent.canceled` → idempotent, ne fait rien si bid déjà supprimé / annulé.

Signature Stripe vérifiée (déjà obligatoire selon CLAUDE.md).

### Endpoints existants modifiés

- `POST /api/v1/bids/{id}/accept` → ajoute `paymentService.capturePaymentIntent(bid.paymentIntentId)` après le changement de statut.
- `POST /api/v1/bids/{id}/reject` → ajoute `paymentService.cancelPaymentIntent(bid.paymentIntentId)` après le changement de statut.
- `POST /api/v1/bids/{id}/cancel` (annulation par l'expéditeur) → si bid `PENDING` (paiement déjà fait), ajoute `cancelPaymentIntent` pour libérer le hold.

L'ancien endpoint `POST /announcements/{id}/bids` est **supprimé** (breaking change). Le frontend Flutter devra migrer vers `/bids/checkout`.

---

## Schedulers

### `AwaitingPaymentCleanupScheduler` (NOUVEAU)

Fréquence : `@Scheduled(fixedRate = 300_000)` — toutes les 5 minutes.

```
1. Récupère tous les bids AWAITING_PAYMENT avec awaiting_payment_expires_at < now()
2. Pour chaque bid :
   a. stripeService.cancelPaymentIntent(bid.paymentIntentId)
   b. Si succès / déjà annulé → bidRepository.deleteById(bid.id)  ← PHYSICAL DELETE
   c. Si Stripe répond payment_intent_unexpected_state ET pi.status = "succeeded" :
      → bid.status = PENDING
      → bid.awaitingPaymentExpiresAt = null
      → audit_log "BID_PROMOTED_RACE_CONDITION"
      → eventPublisher.publishEvent(new BidCreatedEvent(...))
   d. Autre erreur Stripe → log Sentry, retry au prochain tick (idempotence garantie)
```

**Idempotence** : le scheduler doit pouvoir être rejoué sans effet de bord. La condition `status = AWAITING_PAYMENT AND expires_at < now()` est suffisante.

### `BidTimeoutScheduler` (NOUVEAU)

Fréquence : `@Scheduled(fixedRate = 300_000)` — toutes les 5 minutes.

```
1. Récupère tous les bids PENDING avec timeout dépassé :
   - timeout = min(createdAt + 24h, announcement.departureDate - 12h)
2. Pour chaque bid :
   a. bid.status = CANCELLED
   b. bid.rejectionReason = "TRAVELER_NO_RESPONSE"
   c. stripeService.cancelPaymentIntent(bid.paymentIntentId)  ← libère le hold (0 frais)
   d. audit_log "BID_AUTO_CANCELLED_TIMEOUT"
   e. eventPublisher.publishEvent(...)  ← notif "voyageur n'a pas répondu, votre paiement est libéré"
```

---

## Architecture (fichiers)

### Nouveaux fichiers

| Fichier | Rôle |
|---------|------|
| `matching/BidCheckoutService.java` | Orchestration `POST /bids/checkout` : validations + création bid + création PaymentIntent |
| `matching/AwaitingPaymentCleanupScheduler.java` | Suppression physique des bids non payés à T+15min |
| `matching/BidTimeoutScheduler.java` | Auto-annulation des bids `PENDING` non répondus dans le délai |
| `matching/dto/BidCheckoutRequest.java` | DTO entrée checkout (mêmes champs que `BidRequest`) |
| `matching/dto/BidCheckoutResponse.java` | DTO sortie : bidId + clientSecret + publishableKey + expiresAt |
| `payments/StripeWebhookController.java` *(si absent)* | Endpoint webhook Stripe avec vérif signature |
| `db/migration/V37__bids_add_payment_intent.sql` | Migration BDD |

### Fichiers modifiés

| Fichier | Changement |
|---------|------------|
| `matching/BidEntity.java` | + `paymentIntentId`, `awaitingPaymentExpiresAt` |
| `matching/BidStatus.java` | + `AWAITING_PAYMENT` |
| `matching/BidRepository.java` | Filtres + nouvelles requêtes scheduler |
| `matching/BidService.java` | `acceptBid` capture, `rejectBid` cancel, `cancelBid` cancel, filtres listings |
| `matching/BidController.java` | Endpoint `/checkout` ajouté ; ancien `POST /announcements/{id}/bids` retiré |
| `payments/PaymentService.java` | Méthodes publiques `capturePaymentIntent(String)`, `cancelPaymentIntent(String)` |

### Tests requis (couverture ≥ 90 %)

| Test | Type | Cas couverts |
|------|------|--------------|
| `BidCheckoutServiceTest` | Unit | Validations OK / KYC bypass / capacité dépassée / valeur > 500€ / doublon / disclaimer non signé |
| `BidCheckoutIntegrationTest` | Integration MockMvc | 201 succès, 422/409/404 erreurs, sécurité (token absent → 401) |
| `AwaitingPaymentCleanupSchedulerTest` | Unit | Cleanup happy path / race condition (PI succeeded) / erreur Stripe générique |
| `BidTimeoutSchedulerTest` | Unit | Timeout par 24h / timeout par H-12 / pas de timeout si dans la fenêtre |
| `StripeWebhookIntegrationTest` | Integration | Signature invalide → 400, `amount_capturable_updated` → bid promu, idempotence (rejouer le webhook) |
| `BidServiceTest` (mis à jour) | Unit | `acceptBid` capture le PI, `rejectBid` cancel le PI, `cancelBid` cancel le PI si PENDING |

---

## Audit log

| Action | Quand | Acteur |
|--------|-------|--------|
| `BID_CREATED` | Webhook Stripe `amount_capturable_updated` (paiement confirmé) — **plus à la création** | sender |
| `BID_PROMOTED_RACE_CONDITION` | Cleanup scheduler détecte race avec webhook | system |
| `BID_AUTO_CANCELLED_TIMEOUT` | Timeout scheduler | system |
| `BID_CANCELLED` (existant) | `cancelBid` par l'expéditeur | sender |
| `BID_ACCEPTED` (existant) | `acceptBid` | traveler |
| `BID_REJECTED` (existant) | `rejectBid` | traveler |

**Pas d'audit_log pour la création `AWAITING_PAYMENT`** — ces bids supprimés physiquement n'ont jamais d'existence légale.

---

## Race conditions et idempotence

| Scénario | Mitigation |
|----------|------------|
| Webhook Stripe rejoué (Stripe retry) | `if (bid.status != AWAITING_PAYMENT) return;` — idempotent |
| Cleanup scheduler vs webhook au même instant | Cleanup tente `cancel` ; si Stripe répond "succeeded" → promotion (cf. plus haut) |
| Deux schedulers parallèles (instances multiples) | Les requêtes JPA + transactions PostgreSQL garantissent qu'un bid n'est traité qu'une fois (FOR UPDATE SKIP LOCKED en option si déploiement multi-instances) |
| Capacité épuisée entre checkout et acceptation | `acceptBid` valide déjà `weightKg <= availableKg` → 409 CONFLICT → frontend affiche erreur, voyageur peut rejeter → cancel PI (0 frais) |
| Expéditeur ferme l'app avant validation 3DS | PaymentIntent reste `requires_action` côté Stripe → cleanup à T+15min : `cancel` réussit → bid supprimé |

---

## Sécurité

- Webhook Stripe : signature obligatoire (`Stripe-Signature` header) — règle déjà dans CLAUDE.md.
- `POST /bids/checkout` : `@PreAuthorize` non requis (rôle SENDER auto-attribué), mais `FirebaseTokenFilter` garantit auth.
- `paymentIntentId` dans `BidEntity` : sensible mais pas critique. Pas d'exposition dans `BidResponse`.
- Idempotency-Key Stripe : utiliser `bidId` comme `idempotency_key` à la création du PaymentIntent pour éviter les doubles créations.
- Tarif `application_fee_amount` (12 %) reste géré par `PaymentService` existant — inchangé.

---

## Coût Stripe attendu

| Scénario | Frais Stripe |
|----------|--------------|
| Bid créé puis abandonné par l'expéditeur (cleanup à 15min) | **0 €** (PaymentIntent jamais confirmé ou hold annulé) |
| Bid payé puis voyageur refuse | **0 €** (cancel sur hold) |
| Bid payé puis voyageur ne répond pas (timeout) | **0 €** (cancel sur hold) |
| Bid payé + voyageur accepte + livraison confirmée | ~1.5 % + 0.25 € (1 seule fois, sur capture) |

---

## Critères d'acceptation

- [ ] `POST /bids/checkout` crée un bid `AWAITING_PAYMENT` + un PaymentIntent Stripe en mode `manual capture`
- [ ] Aucun `BidCreatedEvent` publié et aucun audit_log avant webhook Stripe
- [ ] Le voyageur ne voit jamais de bid `AWAITING_PAYMENT` dans ses listes
- [ ] L'expéditeur voit son bid `AWAITING_PAYMENT` dans "Mes demandes"
- [ ] Webhook `payment_intent.amount_capturable_updated` promeut le bid en `PENDING`, log audit, publie `BidCreatedEvent`
- [ ] `acceptBid` capture le PaymentIntent ; `rejectBid` et `cancelBid` (sur PENDING) annulent le PaymentIntent
- [ ] `AwaitingPaymentCleanupScheduler` supprime physiquement les bids non payés à T+15min
- [ ] `BidTimeoutScheduler` annule les bids `PENDING` non répondus dans `min(24h, departure-12h)`
- [ ] La race condition cleanup/webhook est gérée (promotion en `PENDING` si PI déjà succeeded)
- [ ] Webhook Stripe vérifie la signature
- [ ] `./mvnw test` passe à 0 rouge
- [ ] Couverture JaCoCo ≥ 90 %
- [ ] Migration V37 testée sur base vierge

---

## Hors scope (à traiter dans une story ultérieure)

- Migration des bids existants (le moment du déploiement, prod aura déjà des bids `PENDING` créés sans paiement — soit on les laisse vivre tels quels, soit on les annule en bulk avec notification ; à décider à la livraison).
- Adaptation du frontend Flutter (autre dépôt) : nouveau endpoint, intégration Stripe PaymentSheet, écran "paiement en cours / reprenez votre paiement".
- Notifications expéditeur lors de l'auto-annulation par timeout (FCM "Votre voyageur n'a pas répondu, paiement libéré").
