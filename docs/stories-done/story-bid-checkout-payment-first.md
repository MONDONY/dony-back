# Story — Bid Checkout Payment-First (Backend)

**Date :** 2026-05-03
**Status :** ✅ Complète (dev/staging seulement — déploiement prod gating sur Q1, Q2, Q6 du spec)
**Branche :** feat/bid-checkout-payment-first

## Résumé
Refonte du flux de réservation : le voyageur reçoit la notification d'une demande **uniquement après** que l'expéditeur a confirmé son paiement Stripe. Implémente un vrai escrow plateforme via le pattern *separate charges and transfers* : capture sur compte plateforme à l'acceptation, puis Transfer manuel vers le voyageur à la livraison confirmée. Inclut un mode legacy `legacy_destination_charge` pour préserver les paiements existants créés avec l'ancien modèle `transfer_data.destination`.

## Fichiers créés
- `src/main/resources/db/migration/V37__bids_add_payment_intent.sql` — colonnes `payment_intent_id`, `awaiting_payment_expires_at` + index partiel scheduler + index payment_intent
- `src/main/resources/db/migration/V38__payments_add_legacy_flag_and_charge_id.sql` — colonnes `legacy_destination_charge` (default false, backfill true sur les rows existants), `stripe_charge_id` + index
- `src/main/java/com/dony/api/matching/BidCheckoutService.java` — orchestration `POST /bids/checkout` (validations métier + insertion `AWAITING_PAYMENT` + délégation PaymentService)
- `src/main/java/com/dony/api/matching/AwaitingPaymentCleanupScheduler.java` — cleanup bids non payés à T+15min (suppression physique, gestion de la race condition `payment_intent_unexpected_state`)
- `src/main/java/com/dony/api/matching/BidTimeoutScheduler.java` — auto-annulation des bids `PENDING` non répondus dans `min(24h, departure - 12h)`
- `src/main/java/com/dony/api/matching/dto/BidCheckoutRequest.java` — DTO entrée checkout
- `src/main/java/com/dony/api/matching/dto/BidCheckoutResponse.java` — DTO sortie (bidId, clientSecret, publishableKey, expiresAt)
- `src/main/java/com/dony/api/payments/BidAcceptedEventListener.java` — capture du PaymentIntent sur le compte plateforme à l'acceptation (uniquement si `legacy = false`)
- `src/test/java/com/dony/api/matching/AwaitingPaymentCleanupSchedulerTest.java`
- `src/test/java/com/dony/api/matching/BidCheckoutControllerIntegrationTest.java`
- `src/test/java/com/dony/api/matching/BidCheckoutServiceTest.java`
- `src/test/java/com/dony/api/matching/BidEntityMigrationTest.java`
- `src/test/java/com/dony/api/matching/BidStatusTest.java`
- `src/test/java/com/dony/api/matching/BidTimeoutSchedulerTest.java`
- `src/test/java/com/dony/api/matching/BidVisibilityTest.java`
- `src/test/java/com/dony/api/matching/dto/BidCheckoutRequestTest.java`
- `src/test/java/com/dony/api/payments/BidAcceptedEventListenerTest.java`
- `src/test/java/com/dony/api/payments/BidRejectedEventListenerTest.java`
- `src/test/java/com/dony/api/payments/DeliveryEventListenerTest.java`
- `src/test/java/com/dony/api/payments/PaymentEntityV38MigrationTest.java`
- `src/test/java/com/dony/api/payments/PaymentServiceCancelCaptureTest.java`
- `src/test/java/com/dony/api/payments/PaymentServiceTestFactory.java`
- `src/test/java/com/dony/api/payments/PaymentWebhookBidPromotionTest.java`
- `src/test/java/com/dony/api/payments/PaymentWebhookChargeIdTest.java`

## Fichiers modifiés
- `src/main/java/com/dony/api/matching/BidStatus.java` — ajout `AWAITING_PAYMENT`
- `src/main/java/com/dony/api/matching/BidEntity.java` — `paymentIntentId`, `awaitingPaymentExpiresAt`
- `src/main/java/com/dony/api/matching/BidRepository.java` — `findByPaymentIntentId`, `findByStatusAndAwaitingPaymentExpiresAtBefore`, `findPendingTimedOut`
- `src/main/java/com/dony/api/matching/BidService.java` — `getBidsForAnnouncement` filtre `AWAITING_PAYMENT` (vue voyageur) ; `cancelBid` publie `BidRejectedEvent(CANCELLED_BY_SENDER)` ; suppression de la publication `BidCreatedEvent` dans `createBid` (désormais publié au webhook)
- `src/main/java/com/dony/api/matching/BidController.java` — endpoint `POST /api/v1/bids/checkout` ajouté ; ancien `POST /announcements/{id}/bids` retiré (breaking change Flutter)
- `src/main/java/com/dony/api/payments/PaymentService.java` — `capturePaymentIntent(String)` et `cancelPaymentIntent(String)` exposées ; `promoteBidOnPaymentAuthorized` (transition `AWAITING_PAYMENT` → `PENDING`, audit log, publication `BidCreatedEvent`) ; `createEscrow` refactoré (drop `transfer_data` + `application_fee_amount`) ; webhook persiste `stripe_charge_id` ; extraction de `dispatchWebhookEvent`
- `src/main/java/com/dony/api/payments/PaymentEntity.java` — `legacyDestinationCharge` (default false), `stripeChargeId`
- `src/main/java/com/dony/api/payments/DeliveryEventListener.java` — dual-path : `pi.capture()` pour les rows legacy, `Transfer.create(...)` (avec `setSourceTransaction(stripeChargeId)`) pour les rows v2 ; `UserRepository` injecté pour récupérer le `stripeAccountId` du voyageur ; `// TODO Q6` documenté sur le calcul net (commission seule, sans frais Transfer)

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

1. Sender appelle `POST /api/v1/bids/checkout` → `BidCheckoutService.checkout`
2. Validations métier (KYC, capacité restante, valeur ≤ 500 €, doublon, ownership, disclaimer signé)
3. Insertion `BidEntity` (status `AWAITING_PAYMENT`, `awaiting_payment_expires_at = now + 15 min`) — invisible aux requêtes voyageur
4. Délégation à `PaymentService.createEscrow` qui crée le `PaymentIntent` Stripe (capture_method=MANUAL, **pas** de `transfer_data` ni `application_fee_amount`)
5. Backfill du `payment_intent_id` sur le bid, retour `clientSecret` au frontend
6. Frontend confirme la carte via PaymentSheet (3DS si nécessaire) → Stripe pose le hold
7. Webhook `payment_intent.amount_capturable_updated` → `PaymentService.dispatchWebhookEvent` → `handlePaymentEscrowActive` :
   - Persiste `stripe_charge_id` (idempotent)
   - Bascule `Payment.status` de `PENDING` à `ESCROW`
   - Appelle `promoteBidOnPaymentAuthorized` → bid passe à `PENDING`, audit log `BID_CREATED`, `BidCreatedEvent` publié → notification voyageur (FCM + SMS)
8. Voyageur accepte → `BidAcceptedEvent` → `BidAcceptedEventListener` capture le PI sur le compte plateforme (`pi.capture()`) — uniquement si `legacy = false`
9. Livraison confirmée (QR scan) → `DeliveryConfirmedEvent` → `DeliveryEventListener` :
   - `legacy = true` → `pi.capture()` (ancien comportement, transfert immédiat au voyageur via `transfer_data`)
   - `legacy = false` → `Transfer.create(amount = total - 12 % commission, destination = travelerStripeAccountId, sourceTransaction = stripeChargeId)`
10. `Payment.status` passe à `RELEASED` dans les deux cas

### Points d'entrée API
- `POST /api/v1/bids/checkout` (rôle SENDER) — crée bid `AWAITING_PAYMENT` + PaymentIntent
- `POST /api/v1/bids/{id}/accept` (TRAVELER) — comportement enrichi : déclenche `BidAcceptedEventListener` qui capture le PI
- `POST /api/v1/bids/{id}/reject` (TRAVELER) — déclenche `BidRejectedEventListener` (cancel hold ou refund selon état)
- `POST /api/v1/bids/{id}/cancel` (SENDER, sur PENDING) — publie `BidRejectedEvent(CANCELLED_BY_SENDER)` qui annule le PI
- `POST /api/v1/payments/webhook` — promotion bid + persistance charge_id + dispatch
- L'ancien `POST /announcements/{id}/bids` est **supprimé** (breaking change Flutter — à coordonner)

### Entités JPA et migrations

**bids** (V37) :
- `payment_intent_id VARCHAR(255)` — FK logique vers Stripe
- `awaiting_payment_expires_at TIMESTAMP` — populé à la création `AWAITING_PAYMENT`, NULL une fois promu `PENDING`
- Index partiel sur `(status, awaiting_payment_expires_at) WHERE status = 'AWAITING_PAYMENT'` pour le scheduler de cleanup
- Index simple sur `payment_intent_id` pour le lookup webhook

**payments** (V38) :
- `legacy_destination_charge BOOLEAN NOT NULL DEFAULT false` — `true` pour les payments d'avant le déploiement v2 (créés avec `transfer_data.destination`), `false` pour les nouveaux. La migration backfill `UPDATE payments SET legacy_destination_charge = true` pour préserver les payments existants.
- `stripe_charge_id VARCHAR(255)` — populé au webhook, utilisé par `Transfer.create(setSourceTransaction(...))` pour la réconciliation comptable Stripe
- Index sur `stripe_charge_id`

### Logique métier critique

**Pourquoi `legacy_destination_charge` ?** Le code v1 (avant cette story) utilisait `transfer_data.destination` à la création du PI : la capture transférait directement les fonds au voyageur. La v2 utilise *separate charges and transfers* : capture sur plateforme + Transfer manuel à la livraison. Migration des PIs en cours : on les laisse vivre sous l'ancien comportement via le flag, qui disparaîtra naturellement quand tous les anciens bids seront résolus (~3-6 mois).

**Pourquoi les bids `AWAITING_PAYMENT` sont supprimés physiquement ?** Ces bids n'ont jamais d'existence légale (pas d'audit_log, pas de notification voyageur). Une suppression physique respecte la demande utilisateur "aucune trace en BDD avant paiement". Exception au principe "soft delete only" du CLAUDE.md, justifiée par l'absence de toute existence métier de ces bids éphémères.

**Pourquoi 15 min de fenêtre de paiement ?** Couvre le pire cas (réseau lent + 3DS + saisie carte). Combiné au scheduler qui tourne toutes les 5 min → délai max effectif 15-20 min avant cleanup.

**Race condition cleanup vs webhook :** Si le scheduler tente d'annuler un PI au moment précis où Stripe le capture (le webhook arrive en parallèle), Stripe répond `payment_intent_unexpected_state`. On lit alors le statut du PI ; s'il est `succeeded` / `requires_capture` / `processing`, on promeut le bid en `PENDING` au lieu de le supprimer (rattrapage), avec un audit log `BID_PROMOTED_RACE_CONDITION`.

**Timeout voyageur :** `min(createdAt + 24h, departureDate - 12h)`. Garantit que la capture intervient bien avant l'expiration du hold Stripe (7 jours).

### Events Spring publiés / écoutés

- `BidCreatedEvent` — désormais publié par `PaymentService.promoteBidOnPaymentAuthorized` (au webhook), plus par `BidService.createBid`. Listener : `NotificationDispatcher.onBidCreated` (FCM + SMS voyageur).
- `BidAcceptedEvent` — publié par `BidService.acceptBid` (inchangé). NOUVEAU listener : `BidAcceptedEventListener.onBidAccepted` (capture PI si non-legacy).
- `BidRejectedEvent` — publié par `BidService.rejectBid` ET désormais par `BidService.cancelBid` (avec raison `CANCELLED_BY_SENDER`) ET par `BidTimeoutScheduler` (raison `TRAVELER_NO_RESPONSE`). Listener : `BidRejectedEventListener` (cancel hold si pas encore capturé, refund si déjà capturé).
- `DeliveryConfirmedEvent` — comportement listener modifié : dual-path (capture pour legacy, Transfer pour v2).

### Pièges et points d'attention

- **TODO Q6** dans `DeliveryEventListener.releaseV2` : si Stripe support révèle des frais de Transfer non-nuls pour les comptes Connect en zone CFA, le calcul `net = total - commission` est faux ; il faudra `net = total - commission - transferFees`. Voir spec section "Q6".
- **Mode legacy à retirer** dans 3-6 mois quand tous les anciens bids `ESCROW legacy=true` seront résolus (livrés, refusés, expirés).
- **Non déployable en prod tel quel** : voir spec section "Actions hors-code à lancer en parallèle du dev" (ticket Stripe support, avis avocat fintech, validation expert-comptable). Pas de mise en prod tant que ces 3 actions n'ont pas abouti.
- **Cleanup scheduler 5 min** : combiné à `awaiting_payment_expires_at = now + 15 min`, le délai effectif avant suppression est entre 15 et 20 minutes selon le timing.
- **`BidCreatedEvent` n'est plus publié à la création du bid** — il a été retiré de `BidService.createBid`. Toute logique métier qui voulait écouter "bid créé en BDD" doit désormais écouter au webhook (`promoteBidOnPaymentAuthorized`).
- **Idempotence du webhook** : `if (bid.status != AWAITING_PAYMENT) return;` — Stripe peut rejouer les webhooks plusieurs fois.
- **Suppression physique des bids `AWAITING_PAYMENT`** : exception explicite au principe "soft delete only" — documentée et justifiée. Ne pas répliquer ce pattern ailleurs sans relecture.

## Critères d'acceptation couverts

- [x] `POST /bids/checkout` crée un bid `AWAITING_PAYMENT` + un PaymentIntent Stripe en `manual capture` — `BidCheckoutService` + `BidController`
- [x] Aucun `BidCreatedEvent` publié et aucun audit_log avant webhook Stripe — commit `23fb0d0`, vérifié dans `BidServiceTest`
- [x] Le voyageur ne voit jamais de bid `AWAITING_PAYMENT` dans ses listes — commit `cdc4cba`, `BidVisibilityTest`
- [x] L'expéditeur voit son bid `AWAITING_PAYMENT` dans "Mes demandes" — `BidVisibilityTest`
- [x] Webhook `payment_intent.amount_capturable_updated` promeut le bid en `PENDING`, log audit, publie `BidCreatedEvent` — `PaymentWebhookBidPromotionTest`
- [x] `acceptBid` capture le PaymentIntent ; `rejectBid`/`cancelBid` annulent le PaymentIntent — `BidAcceptedEventListenerTest`, `BidRejectedEventListenerTest`
- [x] `AwaitingPaymentCleanupScheduler` supprime physiquement les bids non payés à T+15min — `AwaitingPaymentCleanupSchedulerTest`
- [x] `BidTimeoutScheduler` annule les bids `PENDING` non répondus dans `min(24h, departure-12h)` — `BidTimeoutSchedulerTest`
- [x] La race condition cleanup/webhook est gérée (promotion en `PENDING` si PI déjà succeeded) — `AwaitingPaymentCleanupSchedulerTest`
- [x] Webhook Stripe vérifie la signature — comportement existant conservé dans `PaymentService`
- [x] `./mvnw test` passe à 0 rouge
- [x] Couverture JaCoCo ≥ 90 % sur les classes nouvelles/modifiées
- [x] Migration V37 testée sur base vierge — `BidEntityMigrationTest`
- [x] **(v2)** Migration V38 ajoute `legacy_destination_charge` + `stripe_charge_id` ; tous les `payments` existants au moment du déploiement sont marqués `legacy = true` — `PaymentEntityV38MigrationTest`
- [x] **(v2)** `BidAcceptedEventListener` capture le PI **uniquement** pour les payments `legacy = false` — `BidAcceptedEventListenerTest`
- [x] **(v2)** `DeliveryEventListener` branche correctement sur `legacyDestinationCharge` (capture vs Transfer) — `DeliveryEventListenerTest`
- [x] **(v2)** `BidRejectedEventListener` gère la double situation : `cancel` si PI pas encore capturé, `Refund` si déjà capturé — `BidRejectedEventListenerTest`
- [x] **(v2)** Tests des deux flows (legacy et nouveau) en parallèle — couverture dans `DeliveryEventListenerTest` + `BidRejectedEventListenerTest`

## Tests
- `./mvnw test` → 395 tests, 0 failure, 0 error, 6 skipped (préexistants)
- `./mvnw test jacoco:report` → couverture ≥ 90 % sur les classes nouvelles/modifiées :
  - `BidCheckoutService` 90 % lines / 75 % branches
  - `BidAcceptedEventListener` 100 %
  - `AwaitingPaymentCleanupScheduler` 100 %
  - `BidTimeoutScheduler` 100 %
  - `BidRejectedEventListener` 100 %
  - `DeliveryEventListener` 93 %
  - `PaymentService` 97 %
- Tests ajoutés :
  - `BidCheckoutServiceTest`, `BidCheckoutControllerIntegrationTest`, `BidCheckoutRequestTest`
  - `AwaitingPaymentCleanupSchedulerTest`, `BidTimeoutSchedulerTest`
  - `BidStatusTest`, `BidVisibilityTest`, `BidEntityMigrationTest`
  - `BidAcceptedEventListenerTest`, `BidRejectedEventListenerTest`
  - `DeliveryEventListenerTest`, `PaymentEntityV38MigrationTest`
  - `PaymentServiceCancelCaptureTest`, `PaymentServiceTestFactory`
  - `PaymentWebhookBidPromotionTest`, `PaymentWebhookChargeIdTest`

## Décisions techniques

Synthèse des décisions documentées dans le spec (Q1-Q9) :

| # | Décision | Raison |
|---|----------|--------|
| 1 | Paiement **avant** que le voyageur voie la demande | Engagement financier → meilleure qualité de leads |
| 2 | Pré-autorisation (`capture_method=manual`) — capture à l'acceptation | 0 € de frais Stripe sur les refus voyageur |
| 3 | Délai voyageur = `min(24h, departure - 12h)` | Couvre réservation tardive ET très en avance, garantit capture avant les 7 jours d'expiration du hold Stripe |
| 4 | Suppression **physique** des bids `AWAITING_PAYMENT` à T+15min | Demandé par l'utilisateur ; ces bids n'ont aucune existence légale ; exception explicite au "soft delete only" |
| 5 | `AWAITING_PAYMENT` invisible côté voyageur, visible côté expéditeur | Cohérent avec l'objectif (aucune sollicitation tant que non payé) + reprise paiement possible |
| 6 | Modèle Stripe : *separate charges and transfers* | Date de livraison peut être à 2 mois ; le hold Stripe expire à 7 jours → capture à l'acceptation + Transfer manuel à la livraison = vrai escrow plateforme |
| 7 | Commission 12 % conservée sur la plateforme (pas de `application_fee_amount`) | Avec separate charges and transfers, `application_fee_amount` n'est plus utilisable. Commission implicite : on transfère seulement `total - 12 %` |
| 8 | Mode legacy `legacy_destination_charge` pour les anciens PIs | Évite de casser les bids en cours au déploiement. Disparaît naturellement quand tous les anciens payments sont résolus |
| 9 | Race cleanup vs webhook : si Stripe répond `payment_intent_unexpected_state` et PI succeeded → promotion au lieu de suppression | Garantit qu'on ne perd jamais un paiement réussi à cause d'un timing scheduler |

### Mode legacy

Pour identifier les payments legacy en production une fois déployé :
```sql
SELECT * FROM payments WHERE legacy_destination_charge = true;
```

Une fois tous ces payments en statut `RELEASED` ou `REFUNDED`, le mode legacy peut être retiré (suppression de la colonne, du flag, des branches `if (payment.isLegacyDestinationCharge())` dans `BidAcceptedEventListener` et `DeliveryEventListener`).

### Lien spec
- Spec : `docs/superpowers/specs/2026-05-03-bid-checkout-payment-first-design.md` (v2)
- Plan : `docs/superpowers/plans/2026-05-03-bid-checkout-payment-first.md` (PART 1 + PART 2)
- Questions ouvertes (à valider avant prod) : voir spec section "Questions ouvertes / à valider" — Q1 (ticket Stripe support), Q2 (avocat fintech ACPR/DSP2), Q5 (expert-comptable TVA), Q6 (frais Transfer zone CFA — TODO laissé dans le code).
