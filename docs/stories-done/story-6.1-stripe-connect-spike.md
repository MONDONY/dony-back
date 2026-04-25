# Story 6.1 — Spike Stripe Connect Marketplace (Backend)

**Date:** 2026-04-25
**Status:** ✅ Complète

---

## Résumé

Validation de l'intégration Stripe Connect Marketplace en mode test avant toute implémentation dans le projet principal. Le flux complet (escrow → capture → virement → remboursement) a été prototypé et validé dans `StripeConnectSpikeTest.java`.

---

## Fichiers créés

- `src/test/java/com/dony/api/spike/StripeConnectSpikeTest.java` — prototype isolé, 5 tests couvrant le flux complet

---

## Comment ça fonctionne

### Vue d'ensemble du flux Stripe Connect Marketplace

```
Expéditeur paie 35€
      ↓
PaymentIntent créé (capture_method: manual)
      ↓
Argent bloqué chez dony (escrow)
      ↓
Livraison confirmée → DeliveryConfirmedEvent
      ↓
paymentIntent.capture() déclenché
      ↓
Stripe transfère automatiquement vers compte connecté voyageur
Commission dony (12%) retenue via application_fee_amount
      ↓
Voyageur reçoit 30.80€ (sur 35€) — délai J+2 EU / J+5-J+7 Afrique
```

### Flux annulation

```
Voyageur annule → CancellationService
      ↓
refund.create({ payment_intent: id })
      ↓
Expéditeur remboursé intégralement (35€)
Commission dony NON prélevée ✓
Délai : 3-5 jours ouvrés
```

---

## Décisions techniques

### 1. Type de compte connecté : Stripe Express (pas Custom)

**Choix : Stripe Express**

| Critère | Express | Custom |
|---------|---------|--------|
| Onboarding | Stripe gère (formulaire Stripe) | On doit tout construire |
| KYC/conformité | Stripe en est responsable | dony en est responsable |
| Complexité intégration | Faible | Très élevée |
| Dashboard voyageur | Fourni par Stripe | À construire |
| Délai MVP | Semaines | Mois |

Stripe Express est le bon choix pour le MVP. On génère une `AccountLink` vers le formulaire Stripe, l'utilisateur complète son onboarding dans le navigateur/WebView, et Stripe nous notifie via webhook `account.updated` quand `charges_enabled = true`.

### 2. Stratégie de capture : `capture_method: manual`

**Choix : MANUAL (obligatoire pour escrow)**

- `automatic` : capture immédiate → pas d'escrow possible
- `manual` : l'argent est autorisé mais non capturé → escrow maintenu jusqu'à appel explicite de `capture()`
- La capture est déclenchée uniquement par `DeliveryConfirmedEvent` (Stories 6.4)
- Exception : force-release admin à J+48 si le destinataire ne confirme pas (Story 6.5)

### 3. Commission dony : `application_fee_amount`

- Taux : **12%** (configurable via `dony.commission-rate` dans `application.yml`)
- Calculé en centimes : `Math.round(amountCents * 0.12)`
- Prélevé automatiquement par Stripe au moment du transfer vers le compte connecté
- En cas de remboursement : la commission N'EST PAS prélevée (Stripe ne transfère pas si le PI est remboursé avant capture)

### 4. Devise : EUR uniquement pour le MVP

- `currency: "eur"` — les paiements expéditeur se font en euros (France)
- Les voyageurs africains reçoivent en EUR sur leur compte Stripe Express
- Le décaissement en Mobile Money (Wave/Orange Money) est une conversion externe gérée par Story 6.6

---

## Risques identifiés et mitigations

| Risque | Probabilité | Impact | Mitigation |
|--------|-------------|--------|------------|
| Compte connecté non éligible à la capture (KYC incomplet) | Moyen | Haut | Vérifier `charges_enabled` avant d'accepter les bids (Story 6.2) |
| Webhook non reçu (réseau) | Faible | Haut | Idempotence côté backend + retry Stripe automatique (3 tentatives) |
| Délai Stripe J+7 Afrique bloquant le voyageur | Haut | Moyen | Proposer Wave/Orange Money en Story 6.6 (décaissement plus rapide) |
| PaymentIntent expiré (7 jours par défaut) | Faible | Moyen | Créer le PI uniquement au moment du paiement, pas à l'acceptation |
| Double capture accidentelle | Très faible | Haut | Vérifier `payment.status == ESCROW` avant toute capture |

---

## Recommandations pour Stories 6.2 → 6.7

### Story 6.2 — Onboarding voyageur
- Créer le compte Express via `Account.create()` → stocker `account.getId()` dans `UserEntity.stripeAccountId`
- Générer le lien via `AccountLink.create()` avec `type: ACCOUNT_ONBOARDING`
- Webhook `account.updated` → vérifier `charges_enabled = true` → setter `stripeOnboarded = true`
- **Important :** ne pas permettre l'acceptation de bids si `!stripeOnboarded` (le paiement échouera)

### Story 6.3 — Paiement expéditeur
```java
PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
    .setAmount(amountCents)              // poids × pricePerKg × 100
    .setCurrency("eur")
    .setCaptureMethod(CaptureMethod.MANUAL)
    .setApplicationFeeAmount(commissionCents)  // 12%
    .setTransferData(TransferData.builder()
        .setDestination(traveler.getStripeAccountId())
        .build())
    .putMetadata("bid_id",      bid.getId().toString())
    .putMetadata("sender_id",   sender.getId().toString())
    .putMetadata("traveler_id", traveler.getId().toString())
    .build();
```
- Créer `PaymentEntity` avec `status = ESCROW` dès réception du webhook `payment_intent.succeeded`
- **Ne pas créer le PI à l'acceptation** — uniquement quand l'expéditeur clique "Payer"

### Story 6.4 — Déblocage escrow
```java
// Dans DeliveryEventListener
PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
pi.capture(PaymentIntentCaptureParams.builder().build());
```
- Écouter `DeliveryConfirmedEvent` via `@EventListener @Async`
- Mettre à jour `payment.status = RELEASED` + `payment.escrowReleasedAt = NOW()`

### Story 6.5 — Force-release J+48
- Scheduler hourly : trouver `payments` avec `status = ESCROW` et `createdAt < NOW() - 48h`
- Endpoint admin `POST /api/v1/admin/payments/{id}/force-release` → même appel `pi.capture()`
- Exiger photo de livraison géolocalisée avant force-release

### Story 6.6 — Mobile Money
- Stripe ne supporte pas les virements directs vers les wallets africains
- Flux : escrow libéré → virement SEPA sur compte Stripe du voyageur → le voyageur initie lui-même le retrait Wave/Orange Money
- OU : ne pas utiliser Stripe pour le payout, appeler directement l'API Wave/Orange Money avec `PayoutService`
- **Recommandation MVP** : laisser le voyageur choisir SEPA ou Mobile Money dans ses préférences, et appeler Wave/Orange Money API en parallèle si Mobile Money sélectionné

### Story 6.7 — Remboursement
```java
// Dans CancellationService, via PaymentRefundRequestedEvent
RefundCreateParams params = RefundCreateParams.builder()
    .setPaymentIntent(payment.getStripePaymentIntentId())
    .build();
Refund refund = Refund.create(params);
```
- Déclencher uniquement si `payment.status == ESCROW` (avant capture)
- Si déjà capturé (livraison confirmée avant annulation) → litige via disputes package
- Webhook `charge.refunded` → confirmer `payment.status = REFUNDED`

---

## Limitations du mode test vs production

| Aspect | Mode test | Production |
|--------|-----------|------------|
| Virements réels | Jamais (fictifs) | Réels, délai J+2 EU |
| Cartes | 4242... uniquement | Vraies cartes |
| Comptes connectés | Fictifs, pas de KYC réel | KYC Stripe obligatoire |
| Webhooks | Via `stripe listen --forward-to` | HTTPS endpoint public |
| Délais virement | Instantanés en test | J+2 EU, J+5-7 Afrique |
| `charges_enabled` | Toujours true en test | Dépend du KYC voyageur |

---

## Critères d'acceptation couverts

- [x] PaymentIntent créé avec `capture_method: manual` et `transfer_data` → `StripeConnectSpikeTest#spike_03`
- [x] Capture déclenchée et transfer vers compte connecté → `StripeConnectSpikeTest#spike_04`
- [x] Commission dony (`application_fee_amount` 12%) prélevée automatiquement → `spike_03` assertion
- [x] Remboursement intégral sans prélèvement commission → `StripeConnectSpikeTest#spike_05`
- [x] Compte Express créé et lien onboarding généré → `spike_01` + `spike_02`
