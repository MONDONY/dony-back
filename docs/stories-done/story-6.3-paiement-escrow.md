# Story 6.3 — Paiement expéditeur avec création d'escrow (Backend)

**Date:** 2026-04-25
**Status:** ✅ Complète

---

## Résumé

Un expéditeur ayant un bid accepté peut initier le paiement via Stripe Flutter SDK. Le backend crée un `PaymentIntent` en mode `capture_method: manual` (escrow), et enregistre le paiement en base avec `status = PENDING`. Le webhook `payment_intent.amount_capturable_updated` (carte autorisée, fonds retenus) passe le statut en `ESCROW`.

---

## Fichiers créés

- `src/main/resources/db/migration/V19__payments_add_deleted_at.sql` — colonne `deleted_at` sur `payments` (aligne sur `BaseEntity`)
- `payments/PaymentStatus.java` — enum: `PENDING`, `ESCROW`, `RELEASED`, `FAILED`, `REFUNDED`
- `payments/PaymentEntity.java` — entité JPA sur table `payments` (créée en V5)
- `payments/PaymentRepository.java` — `findByBidId`, `findByStripePaymentIntentId`, `findByStatus`
- `payments/dto/CreatePaymentRequest.java` — `{ bidId: UUID }`
- `payments/dto/PaymentResponse.java` — `{ id, bidId, clientSecret, amount, commissionAmount, status }`

## Fichiers modifiés

- `payments/PaymentService.java` — ajout `createEscrow()` + handlers webhook `payment_intent.amount_capturable_updated` et `payment_intent.payment_failed`
- `payments/PaymentController.java` — ajout `POST /payments` avec `ROLE_SENDER`

---

## Comment ça fonctionne

### Flux complet (vue expéditeur)

```
1. Expéditeur ouvre l'écran "Payer mon envoi" (bid accepté)
        ↓
2. POST /payments  { bidId }
   → Vérifie sender = propriétaire du bid
   → Vérifie bid.status = ACCEPTED
   → Récupère l'annonce → pricePerKg + travelerId
   → Vérifie traveler.stripeOnboarded = true
   → Calcule amount = weightKg × pricePerKg
   → Calcule commission = amount × 12%
   → Crée PaymentIntent Stripe (capture_method: MANUAL, application_fee_amount, transfer_data)
   → Sauvegarde PaymentEntity(status=PENDING)
   → Retourne { clientSecret, amount, commissionAmount }
        ↓
3. Flutter: initPaymentSheet + presentPaymentSheet avec clientSecret
   → Carte autorisée → Stripe envoie webhook payment_intent.amount_capturable_updated
        ↓
4. Webhook payment_intent.amount_capturable_updated
   → PaymentEntity.status = ESCROW
   → audit_log: PAYMENT_ESCROW_ACTIVE
```

### Points d'entrée API

- `POST /api/v1/payments` — `ROLE_SENDER` requis. Crée l'escrow. Idempotent : si un `PaymentEntity` non-`FAILED` existe pour ce `bidId`, retourne le `clientSecret` de l'intent existant.
- `POST /api/v1/payments/webhook` — Public. Gère maintenant : `account.updated`, `payment_intent.amount_capturable_updated`, `payment_intent.payment_failed`.

### Entités JPA impliquées

- `PaymentEntity` → table `payments` (V5) avec `deleted_at` ajouté en V19 :
  - `bid_id UUID UNIQUE` — une seule tentative de paiement active par bid
  - `stripe_payment_intent_id VARCHAR UNIQUE` — ID Stripe de l'intent
  - `amount DECIMAL(10,2)` — ce que l'expéditeur paye (weightKg × pricePerKg)
  - `commission_amount DECIMAL(10,2)` — 12% prélevés du côté voyageur
  - `status VARCHAR(30)` — `PENDING` → `ESCROW` (ou `FAILED`)
  - `escrow_released_at` — rempli en Story 6.4

### Logique métier critique

**Calcul du montant** : `amount = bid.weightKg × announcement.pricePerKg`. La commission (12%) est prélevée de la part du voyageur via `application_fee_amount` — l'expéditeur paie uniquement `amount`.

**Idempotence** : si un `PaymentEntity` existe avec status != `FAILED`, on récupère le `clientSecret` depuis Stripe plutôt que créer un nouveau `PaymentIntent`. Stripe ne permet pas deux intents actifs pour la même transaction.

**Webhook `payment_intent.amount_capturable_updated` vs `payment_intent.succeeded`** : la story spécifiait `payment_intent.succeeded`, mais avec `capture_method: manual`, cet événement ne se déclenche qu'après la capture. Le bon événement pour "carte autorisée, fonds retenus" est `payment_intent.amount_capturable_updated`. Voir Story 6.4 pour la capture.

**Guard `charges_enabled` du voyageur** : si `traveler.stripeOnboarded = false`, l'API retourne `422` — le voyageur doit d'abord compléter l'onboarding (Story 6.2).

**Commission configurée** : `dony.commission.rate` (défaut 0.12) — changeable sans recompiler.

### Pièges et points d'attention

- La table `payments` (V5) n'avait pas de `deleted_at`. V19 l'ajoute pour aligner sur `BaseEntity`. Sans ça, Hibernate ne peut pas mapper `@Where(clause = "deleted_at IS NULL")`.
- `transferData.destination` = `traveler.stripeAccountId` — le transfert vers le voyageur est automatique à la capture (Story 6.4).
- `clientSecret` ne doit jamais être loggué (il permet d'initier des actions sur l'intent). Il est retourné dans la réponse mais pas stocké en base.

---

## Critères d'acceptation couverts

- [x] **Given** un expéditeur avec un bid accepté **When** il saisit ses informations de carte via Stripe Flutter SDK **Then** un PaymentIntent Stripe est créé avec `capture_method: manual` → `createEscrow()` crée l'intent et sauvegarde `PaymentEntity(status=PENDING)`
- [x] **And** `payment.status = ESCROW` est enregistré en base → webhook `payment_intent.amount_capturable_updated` → `handlePaymentEscrowActive()`
- [x] **And** aucun virement n'est déclenché vers le voyageur à ce stade → `capture_method: manual` garantit l'absence de transfer immédiat
- [x] **Given** un paiement Stripe échoué **When** le webhook `payment_intent.payment_failed` arrive **Then** `payment.status = FAILED` → `handlePaymentFailed()`
- [x] **Given** une commission dony de 12% sur 50€ **Then** `application_fee_amount = 600` centimes → `commission = amount × commissionRate (0.12)`

## Décisions techniques

**`payment_intent.amount_capturable_updated` plutôt que `payment_intent.succeeded`** : avec `capture_method: manual`, `payment_intent.succeeded` ne se déclenche qu'après la capture explicite (Story 6.4). Le bon signal pour "fonds en escrow" est `amount_capturable_updated`.

**Pas de `@Transactional` sur `handlePaymentEscrowActive`** : le webhook peut arriver deux fois (retry Stripe). Le guard `if (payment.getStatus() == PENDING)` protège contre les doubles mises à jour.

**`V19` plutôt que modifier V5** : règle absolue Flyway — ne jamais modifier une migration existante.
