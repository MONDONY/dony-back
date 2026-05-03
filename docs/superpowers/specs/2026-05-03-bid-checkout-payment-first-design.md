# Bid Checkout — Payment-First Reservation Flow

**Date:** 2026-05-03
**Status:** Spec v2 — en attente de re-validation (escrow plateforme ajouté)
**Auteur:** Claude (brainstorming guidé)

## Historique des révisions

| Version | Date | Changements |
|---------|------|-------------|
| v1 | 2026-05-03 (matin) | Spec initiale : pré-autorisation Stripe, capture à l'acceptation, `transfer_data.destination` (le voyageur reçoit l'argent à l'acceptation). Implémentée Tasks 1-8. |
| **v2** | 2026-05-03 (après-midi) | **Refonte de l'escrow** : modèle "separate charges and transfers". L'argent est capturé sur le compte plateforme à l'acceptation, puis transféré au voyageur **uniquement à la confirmation de livraison**. Compatible avec délais voyage > 7 jours. Migration nécessaire pour les `PaymentIntent` existants (mode legacy). |

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
| 2 | Capture immédiate ou pré-autorisation ? | **Pré-autorisation** (`capture_method=manual`, capture à l'acceptation du voyageur) | 0 € de frais Stripe sur les refus voyageur (vs ~0.70 €/refus en capture immédiate) |
| 3 | Délai max de réponse voyageur | `min(24h après création du bid, departureDate - 12h)` | Couvre réservation tardive ET très en avance. Garantit que la capture se produit bien avant les 7 jours d'expiration du hold Stripe |
| 4 | Trace en BDD avant paiement | **Oui**, `status = AWAITING_PAYMENT`, **suppression physique** par scheduler 5 minutes après expiration (15 min après création) | Demandé explicitement par l'utilisateur. Ces bids n'ont jamais d'existence légale (jamais notifiés, jamais audités) → exception au "soft delete only" du CLAUDE.md, justifiée |
| 5 | Visibilité côté voyageur | `AWAITING_PAYMENT` invisible dans toutes les listes voyageur | Cohérent avec l'objectif : aucune sollicitation tant que non payé |
| 6 | Visibilité côté expéditeur | `AWAITING_PAYMENT` visible dans "Mes demandes" | Pour permettre la reprise du paiement si interruption |
| **7** | **Modèle d'escrow Stripe** | **Separate charges and transfers (v2)** : à l'acceptation, capture vers le compte plateforme (PAS sur le compte voyageur). Transfer vers le voyageur **uniquement à la confirmation de livraison**. | La date de livraison peut être à 2 mois (selon le vol du voyageur). Le hold Stripe expire à 7 jours, donc capturer plus tard est impossible. Capturer plus tôt (à l'acceptation) tout en gardant l'argent sur la plateforme jusqu'à livraison = vrai escrow plateforme. |
| **8** | **Frais commission (12%)** | Conservés sur le compte plateforme **avant** le Transfer (donc le Transfer envoie `total - commission` au voyageur). Pas de `application_fee_amount` Stripe — implémentation manuelle côté code. | Avec le pattern separate charges and transfers, `application_fee_amount` n'est plus utilisable. La commission est implicite : la plateforme ne transfère que `montant_net = total - 12%`. |
| **9** | **Compatibilité avec les `PaymentIntent` existants** | **Mode legacy dual-path** : les payments créés avant le déploiement v2 ont le flag `legacy_destination_charge = true` → continuent d'utiliser l'ancien comportement (capture à la livraison via `transfer_data.destination`). Les nouveaux payments (`legacy = false`) suivent le nouveau modèle. | Évite de casser les bids en cours au moment du déploiement. Le mode legacy disparaît naturellement quand tous les payments antérieurs sont résolus. |

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
    │                       │ → débit réel             │                      │
    │                       │ → argent sur compte      │                      │
    │                       │   PLATEFORME (PAS le     │                      │
    │                       │   compte du voyageur)    │                      │
    │                       │ → bid.status = ACCEPTED  │                      │
    │                       │ → payment.status = ESCROW│                      │
    │                       │                          │                      │
    │                       │       (livraison confirmée — QR scan)           │
    │                       │◄────────────────────────────────────────────────┤
    │                       │ Transfer.create          │                      │
    │                       │   amount = total - 12%   │                      │
    │                       │   destination = voyageur │                      │
    │                       ├─────────────────────────►│                      │
    │                       │ → argent envoyé au       │                      │
    │                       │   voyageur               │                      │
    │                       │ → payment.status=RELEASED│                      │
```

**Cas alternatifs** :

- Expéditeur abandonne le checkout → scheduler supprime le bid + cancel le PaymentIntent à T+15min (0 frais).
- Voyageur refuse **avant capture** (bid PENDING) → `paymentIntent.cancel()` → hold libéré (0 frais).
- Voyageur ne répond pas dans `min(24h, departure-12h)` → scheduler timeout : bid CANCELLED + `paymentIntent.cancel()` (0 frais).
- Voyageur refuse **après capture** (bid ACCEPTED, refus parcel à l'inspection) → `Refund.create()` → l'argent retourne sur la carte de l'expéditeur (frais Stripe ~0.70 € perdus).
- Litige tranché en faveur de l'expéditeur après livraison → `Refund.create()` (frais perdus). L'argent est encore sur le compte plateforme (pas encore transféré) si pas livré ; après livraison + transfer, il faut un `Transfer.createReversal()` puis `Refund.create()`.
- Race condition (scheduler vs webhook au même instant) : si Stripe répond `payment_intent_unexpected_state` au cancel et le PI est `succeeded`, on promeut le bid en `PENDING` (rattrapage).

---

## Architecture Stripe — Separate Charges and Transfers (v2)

### Pourquoi changer l'architecture ?

Le code v1 (encore en place avant cette refonte) utilise le pattern Stripe **destination charges** : chaque `PaymentIntent` est créé avec `transfer_data.destination = traveler.stripeAccountId` et `application_fee_amount = 12%`. Cela signifie que **dès la capture**, l'argent quitte la plateforme et arrive sur le compte Connect du voyageur.

Conséquences inacceptables pour Dony :

1. **Capture à l'acceptation = paiement immédiat au voyageur**, avant qu'il n'ait voyagé. Pas de séquestre réel.
2. **Capture à la livraison** (l'autre option) impossible : le hold Stripe expire après **7 jours** alors qu'un voyage peut être planifié à **2 mois** d'écart.

### Nouveau modèle : separate charges and transfers

Le PaymentIntent ne référence **pas** le compte Connect du voyageur. L'argent est capturé sur le **compte plateforme**, puis transféré au voyageur via une opération Stripe distincte (`Transfer.create`) **uniquement à la confirmation de livraison**.

```
Acceptation                                Livraison confirmée
    │                                              │
    ▼                                              ▼
PaymentIntent.capture()                  Transfer.create({
    → argent débité de la carte sender     amount: total - 12%,
    → arrive sur PLATEFORME                destination: traveler_stripe_id,
    → payment.status = ESCROW              source_transaction: pi.charge_id
                                          })
                                            → argent envoyé au voyageur
                                            → payment.status = RELEASED
```

### Configuration Stripe Connect requise

| Élément | État actuel | Compatible nouveau modèle ? |
|---------|-------------|------------------------------|
| Type de compte voyageur | **Express** (`PaymentService.java:94`) | ✅ Oui |
| Capability `transfers` | ✅ Activée (`PaymentService.java:98`) | ✅ Oui (suffisante pour recevoir des Transfers) |
| Capability `card_payments` | Non requise | ✅ Pas nécessaire pour le voyageur dans ce modèle |

**Aucun re-onboarding voyageur n'est nécessaire.** La capability `transfers` (déjà activée) est tout ce qu'il faut côté compte connecté.

### Comparaison côté code

```java
// AVANT (v1, destination charge)
PaymentIntentCreateParams params = builder
    .setCaptureMethod(MANUAL)
    .setApplicationFeeAmount(commissionCents)
    .setTransferData(TransferData.builder()
        .setDestination(traveler.getStripeAccountId())  // ← lien direct
        .build())
    .build();

// APRÈS (v2, separate charges and transfers)
PaymentIntentCreateParams params = builder
    .setCaptureMethod(MANUAL)
    // PAS d'application_fee_amount, PAS de transfer_data
    .putMetadata("bid_id", bidId)
    .putMetadata("traveler_id", travelerId)
    .build();

// Plus tard, à la livraison :
TransferCreateParams transferParams = TransferCreateParams.builder()
    .setAmount(amountToTraveler)  // total - 12% de commission
    .setCurrency("eur")
    .setDestination(traveler.getStripeAccountId())
    .setSourceTransaction(payment.getStripeChargeId())  // lie le transfer à la charge
    .putMetadata("bid_id", bidId.toString())
    .build();
Transfer.create(transferParams);
```

### Mode legacy (compatibilité avec les `PaymentIntent` existants)

Au moment du déploiement v2, certains `Payment` rows existent déjà avec un `PaymentIntent` configuré en **destination charge**. Ces PIs vont continuer à se comporter à l'ancienne (capture → transfert immédiat au voyageur). Pour que le code post-déploiement gère correctement les deux flux :

- **Nouvelle colonne** sur `payments` : `legacy_destination_charge BOOLEAN NOT NULL DEFAULT false`.
- **Migration de données** : tous les `payments` créés *avant* le déploiement → `legacy_destination_charge = true`.
- **`DeliveryEventListener`** branche sur ce flag :
  - `legacy = true` → ancien comportement (`pi.capture()` à la livraison, qui transfère directement au voyageur via `transfer_data`).
  - `legacy = false` → nouveau comportement (`Transfer.create()` à la livraison, capture déjà faite à l'acceptation).
- **`BidAcceptedEventListener`** ne capture le PI que pour les `legacy = false`.

Ce mode legacy disparaît naturellement quand tous les bids antérieurs sont résolus (livrés, refusés ou expirés). Il pourra être supprimé du code dans une PR ultérieure (~3-6 mois après le déploiement v2).

### Implications côté `PaymentEntity` et `PaymentStatus`

**Nouveau champ** sur `PaymentEntity` :
```java
@Column(name = "stripe_charge_id", length = 255)
private String stripeChargeId;  // populé au webhook charge.succeeded ou via PI.charges.data[0]
```

`stripeChargeId` est nécessaire pour `setSourceTransaction(...)` lors du Transfer (lie le transfer à la charge originale, important pour la réconciliation comptable Stripe).

**Statuts existants conservés** :
- `PENDING` → PI créé, pas encore confirmé par carte
- `ESCROW` → PI confirmé (hold posé) **OU** capturé sur compte plateforme (selon legacy ou non)
- `RELEASED` → argent envoyé au voyageur (capture pour legacy, Transfer pour v2)
- `REFUNDED` → remboursé à l'expéditeur
- `FAILED` → échec carte ou Stripe

Pas de nouveau statut requis. `ESCROW` couvre désormais deux situations : "hold actif" (legacy avant capture) et "captured sur plateforme" (v2 après capture, avant Transfer). La distinction est portée par `legacy_destination_charge`.

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

### Migration `V38__payments_add_legacy_flag_and_charge_id.sql` (NOUVEAU v2)

```sql
ALTER TABLE payments
  ADD COLUMN legacy_destination_charge BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN stripe_charge_id          VARCHAR(255);

-- Tous les payments existants au moment du déploiement utilisent l'ancien flow
UPDATE payments SET legacy_destination_charge = true;

CREATE INDEX idx_payments_stripe_charge_id ON payments (stripe_charge_id);

COMMENT ON COLUMN payments.legacy_destination_charge IS 'true si le PaymentIntent a été créé avec transfer_data.destination (capture transfère directement au voyageur). false pour le nouveau modèle separate-charges-and-transfers.';
COMMENT ON COLUMN payments.stripe_charge_id IS 'Stripe Charge id, populé au webhook payment_intent.amount_capturable_updated. Nécessaire pour Transfer.create avec source_transaction.';
```

### Champs ajoutés sur `PaymentEntity`

```java
@Column(name = "legacy_destination_charge", nullable = false)
private boolean legacyDestinationCharge = false;  // false pour les nouveaux paiements

@Column(name = "stripe_charge_id", length = 255)
private String stripeChargeId;
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

- `POST /api/v1/bids/{id}/accept` :
  - Bid passe à `ACCEPTED` (inchangé)
  - **NOUVEAU v2** : `BidAcceptedEventListener` capture le PI sur le compte plateforme (`pi.capture()`). Pour les payments `legacy_destination_charge = true`, **pas** de capture ici — la capture historique restera à la livraison via `DeliveryEventListener`.
  - `Payment.status` passe de `ESCROW` (qui désormais signifie "captured on platform") → reste `ESCROW` (le statut couvre les deux situations).
- `POST /api/v1/bids/{id}/reject` (avant capture, bid PENDING) → `paymentService.cancelPaymentIntent(...)` (libère le hold, 0 frais).
- `POST /api/v1/bids/{id}/cancel` (annulation par l'expéditeur, bid PENDING) → idem `reject` côté Stripe.
- **Refus parcel à l'inspection** (bid passe `PARCEL_REFUSED` après acceptation) → `Refund.create()` car la capture a déjà eu lieu (frais Stripe perdus).

### Endpoint inchangé mais comportement modifié — Confirmation de livraison

`DeliveryEventListener.handleDeliveryConfirmed` (Story 6.4) :

- **`legacy_destination_charge = true`** (paiement créé avant le déploiement v2) → comportement actuel conservé : `pi.capture()` à la livraison, l'argent transite directement au voyageur via `transfer_data.destination`.
- **`legacy_destination_charge = false`** (nouveau modèle) → `Transfer.create()` avec `amount = total - 12 %` et `destination = traveler.stripeAccountId`. La capture a déjà eu lieu à l'acceptation.

Dans les deux cas, `Payment.status` passe à `RELEASED` à la fin du flux.

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
| `db/migration/V37__bids_add_payment_intent.sql` | Migration BDD (déjà créé Tasks 1-8) |
| **`db/migration/V38__payments_add_legacy_flag_and_charge_id.sql`** *(v2)* | Ajoute `legacy_destination_charge` + `stripe_charge_id` sur `payments` |
| **`payments/BidAcceptedEventListener.java`** *(v2)* | Écoute `BidAcceptedEvent` ; capture le PI sur le compte plateforme (uniquement si `legacy = false`) |
| **`payments/BidCancelledByOwnerEvent.java` + listener** *(v2)* | Annule le PI si l'expéditeur annule un bid `PENDING` |

### Fichiers modifiés

| Fichier | Changement |
|---------|------------|
| `matching/BidEntity.java` | + `paymentIntentId`, `awaitingPaymentExpiresAt` |
| `matching/BidStatus.java` | + `AWAITING_PAYMENT` |
| `matching/BidRepository.java` | Filtres + nouvelles requêtes scheduler |
| `matching/BidService.java` | `acceptBid` capture, `rejectBid` cancel, `cancelBid` cancel, filtres listings |
| `matching/BidController.java` | Endpoint `/checkout` ajouté ; ancien `POST /announcements/{id}/bids` retiré |
| `payments/PaymentService.java` | Méthodes publiques `capturePaymentIntent(String)`, `cancelPaymentIntent(String)` ; **v2 :** `createEscrow` adapté pour ne plus poser `transfer_data` ni `application_fee_amount` (modèle separate charges and transfers) |
| **`payments/PaymentEntity.java`** *(v2)* | + `legacyDestinationCharge` (boolean) + `stripeChargeId` (String) |
| **`payments/DeliveryEventListener.java`** *(v2)* | Branche sur `legacyDestinationCharge` : ancien `pi.capture()` pour legacy, nouveau `Transfer.create()` (avec `amount = total - 12 %`) pour les nouveaux paiements |
| **`payments/BidRejectedEventListener.java`** *(v2)* | Si bid était `ACCEPTED` (capture déjà effectuée) → `Refund.create()` au lieu de `pi.cancel()` |

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
| Bid payé puis voyageur refuse avant acceptation | **0 €** (cancel sur hold) |
| Bid payé puis voyageur ne répond pas (timeout) | **0 €** (cancel sur hold) |
| Bid payé + voyageur accepte + livraison confirmée | ~1.5 % + 0.25 € sur la **capture** + frais Stripe Transfer (généralement gratuit en EUR) — soit ~0.70 € sur 30 € |
| Bid payé + voyageur accepte mais refus parcel à inspection | ~0.70 € (frais de capture perdus, refund ne récupère pas les frais Stripe) |
| Litige tranché en faveur de l'expéditeur après livraison | ~0.70 € + frais Transfer Reversal (généralement gratuit) |

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
- [ ] **(v2)** Migration V38 ajoute `legacy_destination_charge` + `stripe_charge_id` ; tous les `payments` existants au moment du déploiement sont marqués `legacy = true`
- [ ] **(v2)** `BidAcceptedEventListener` capture le PI **uniquement** pour les payments `legacy = false`
- [ ] **(v2)** `DeliveryEventListener` branche correctement sur `legacyDestinationCharge` (capture vs Transfer)
- [ ] **(v2)** `BidRejectedEventListener` gère la double situation : `cancel` si PI pas encore capturé, `Refund` si déjà capturé
- [ ] **(v2)** Tests des deux flows (legacy et nouveau) en parallèle pour vérifier la rétro-compatibilité

---

## Questions ouvertes / à valider

> ⚠️ Cette section liste les points qui dépassent la simple décision technique et doivent être validés **avant la mise en production**, voire **avant la fin de l'implémentation v2** pour certains.

### Q1 — Type de compte Stripe Connect actuel et compatibilité avec l'approche

**État actuel** : comptes voyageurs en **Stripe Connect Express** avec capability `transfers` activée (`PaymentService.java:94-99`).

**Compatibilité** : ✅ confirmée. Le pattern *separate charges and transfers* fonctionne avec Express, Standard et Custom. La capability `transfers` (déjà active) suffit pour recevoir un `Transfer.create`. Aucun re-onboarding voyageur nécessaire.

**À valider** : confirmer auprès du **support Stripe** qu'il n'y a pas d'autres prérequis spécifiques au pays Sénégal/France (ex. KYB plateforme, identification fiscale). Ouvrir un ticket Stripe support avant prod.

### Q2 — Implications conformité (détenir des fonds clients sur le compte plateforme)

**Constat** : avec ce nouveau modèle, l'argent reste sur le compte Stripe de la plateforme entre l'acceptation et la livraison — potentiellement **2 mois** dans le pire cas (vol planifié à long terme).

**Risque réglementaire France/UE** :
- DSP2 / ACPR : la détention de fonds clients > "délai raisonnable" peut requérir un statut **Agent PSP** ou **Établissement de Monnaie Électronique (EME)**.
- Stripe couvre une partie du risque via leur licence, mais le seuil exact dépend du volume et de la durée moyenne.

**Action requise avant prod** :
1. **Ouvrir un ticket avec le support Stripe** : décrire le use case (marketplace P2P, fonds détenus jusqu'à 2 mois) et demander confirmation que c'est OK avec leur licence et leurs conditions d'utilisation.
2. **Consulter un avocat fintech** (compter ~1500-3000 €) pour un avis écrit sur la conformité ACPR. Si le volume reste < 1M€/an et la durée moyenne < 1 mois, généralement pas de requalification ; au-delà, il faut probablement un statut intermédiaire.
3. **Documenter** la décision dans `docs/compliance/` (à créer).

**Tant que ces deux validations ne sont pas faites, ne pas déployer en production.** Le développement peut continuer en environnement dev/staging avec test cards Stripe.

### Q3 — Stratégie de migration pour les `PaymentIntent` existants

**Décision proposée** : mode **dual-path** via le flag `legacy_destination_charge`.

- Tous les payments créés avant le déploiement v2 → `legacy = true` → `DeliveryEventListener` continue d'utiliser `pi.capture()` (qui transfère directement au voyageur via `transfer_data.destination` posé à la création).
- Tous les payments créés après le déploiement v2 → `legacy = false` → nouveau flow.

**Avantages** :
- ✅ Zéro disruption pour les bids en cours.
- ✅ Pas de remboursement / re-création nécessaire.
- ✅ Le mode legacy disparaît naturellement quand les anciens bids sont tous résolus (3-6 mois).

**Risque résiduel** :
- Les anciens PIs avec `transfer_data.destination` ont leur hold qui expire à **7 jours**. Si un voyage planifié à plus de 7 jours après la création du PI legacy n'est pas livré dans les temps → la capture échouera. **Mais ce risque existait déjà avant la v2** — il n'est pas introduit par cette refonte.

**Action** : faire un audit avant le déploiement pour identifier les payments `ESCROW` avec `created_at < now() - 6 jours` et les notifier ou les capturer manuellement si la livraison est imminente.

### Q4 — Gestion des remboursements partiels et litiges dans le nouveau modèle

**Cas à traiter** :

| Cas | Action côté Stripe | Frais perdus |
|-----|---------------------|--------------|
| Refus parcel à inspection (avant Transfer) | `Refund.create(charge_id)` — argent encore sur compte plateforme | ~0.70 € |
| Litige tranché expéditeur après livraison (déjà transféré) | 1. `Transfer.createReversal()` pour récupérer l'argent du compte voyageur. 2. `Refund.create(charge_id)` pour rendre à la carte. | ~0.70 € + 0 € reversal |
| Litige partiel (ex. 50 % remboursé) | `Refund.create(amount=50%)` — Stripe gère le partial. Si déjà transféré : reversal partiel d'abord. | proportionnel |

**Question ouverte** : qui décide du remboursement ? Le `DisputeService` existe (Story 8.x) — vérifier qu'il sait piloter ces opérations ou créer une interface admin.

**À ajouter dans une story ultérieure** (hors v2 : focus sur le happy path d'abord).

### Q5 — Impact comptabilité / facturation

**Avant (v1, destination charges)** :
- La plateforme n'est techniquement qu'un facilitateur. La facture client (sender) est émise *par Stripe au nom de la plateforme*. Le voyageur reçoit son paiement net comme un revenu d'activité indépendante.
- Les `application_fee_amount` apparaissent sur le compte plateforme comme commissions, faciles à comptabiliser.

**Après (v2, separate charges and transfers)** :
- La plateforme **encaisse** le montant total puis **reverse** au voyageur. Comptablement, c'est un flux différent :
  - Côté plateforme : produit = montant total ; charge = montant transféré au voyageur ; produit net = commission.
  - Risque de confusion sur la TVA si l'auto-liquidation s'applique (intermédiation transparente vs opaque).
- Les Transfers Stripe vers les voyageurs n'apparaissent plus comme `application_fee_amount` mais comme des sorties d'argent de la plateforme.

**Action requise** :
1. Faire valider par un **expert-comptable** que ce schéma est compatible avec la comptabilité actuelle de la plateforme (ou s'il faut un nouveau plan comptable).
2. Vérifier la **TVA** : si Dony est intermédiaire transparent, OK ; si requalifié en intermédiaire opaque, la TVA s'applique sur le total et non sur la commission.
3. Documenter le schéma des écritures dans `docs/accounting/` (à créer).

### Q6 — Frais Stripe Transfer (gratuits en EUR ?)

**Hypothèse de travail** : les Transfers Stripe entre comptes EUR sont gratuits dans la zone SEPA. Mais Dony cible la diaspora africaine : les voyageurs peuvent avoir des comptes connectés au Sénégal, Côte d'Ivoire, Mali, Cameroun.

**Question** : quel est le coût d'un Transfer EUR → compte Stripe Connect d'un voyageur en zone CFA ?

**Action** : confirmer auprès de Stripe support **avant prod**. Si non-gratuit, recalibrer la commission (actuellement 12 %) pour absorber les frais.

---

## Hors scope (à traiter dans une story ultérieure)

- Migration des bids existants (le moment du déploiement, prod aura déjà des bids `PENDING` créés sans paiement — soit on les laisse vivre tels quels, soit on les annule en bulk avec notification ; à décider à la livraison).
- Adaptation du frontend Flutter (autre dépôt) : nouveau endpoint, intégration Stripe PaymentSheet, écran "paiement en cours / reprenez votre paiement".
- Notifications expéditeur lors de l'auto-annulation par timeout (FCM "Votre voyageur n'a pas répondu, paiement libéré").
- Interface admin pour piloter les refunds partiels et les Transfer Reversals (litiges complexes).
- Suppression du mode legacy (`legacy_destination_charge`) une fois tous les anciens payments résolus (~3-6 mois post-déploiement v2).
