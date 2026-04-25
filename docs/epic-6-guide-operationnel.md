# Epic 6 — Guide opérationnel Paiement Escrow (Stripe Connect)

**Dernière mise à jour :** 2026-04-26
**Statut de l'epic :** ✅ Complète (6.6 Mobile Money skippée)

Ce guide explique comment faire fonctionner, tester et opérer chaque story de l'Epic 6 en environnement de développement et de production.

---

## Sommaire

1. [Prérequis et configuration](#1-prérequis-et-configuration)
2. [Story 6.2 — Onboarding voyageur (compte bancaire)](#2-story-62--onboarding-voyageur-compte-bancaire)
3. [Story 6.3 — Paiement expéditeur (escrow)](#3-story-63--paiement-expéditeur-escrow)
4. [Story 6.4 — Déblocage automatique après livraison](#4-story-64--déblocage-automatique-après-livraison)
5. [Story 6.5 — Panel admin : déblocage manuel J+48](#5-story-65--panel-admin--déblocage-manuel-j48)
6. [Story 6.7 — Remboursement automatique sur annulation](#6-story-67--remboursement-automatique-sur-annulation)
7. [Vérifications en base de données](#7-vérifications-en-base-de-données)
8. [Erreurs fréquentes et solutions](#8-erreurs-fréquentes-et-solutions)

---

## 1. Prérequis et configuration

### Variables d'environnement obligatoires

```bash
# Fichier .env.dev (ne pas committer)
export STRIPE_SECRET_KEY=sk_test_51...      # Clé secrète test Stripe
export STRIPE_WEBHOOK_SECRET=whsec_...      # Secret du webhook Stripe CLI (développement)
```

Chargement avant démarrage du backend :
```bash
source .env.dev && ./mvnw spring-boot:run -Dspring.profiles.active=dev
```

### Webhook Stripe CLI (développement uniquement)

Le webhook Stripe CLI fait le pont entre Stripe et ton backend local.
Il doit tourner en **parallèle** du backend pendant tous les tests de paiement.

```bash
# Dans un terminal dédié — à laisser tourner
stripe listen --forward-to localhost:8080/api/v1/payments/webhook
```

La commande affiche un `whsec_xxx` au démarrage. Ce secret doit être dans `STRIPE_WEBHOOK_SECRET`.

**Important :** si tu relances le CLI Stripe, le `whsec_` change. Il faut relancer le backend avec le nouveau secret.

### Vérifier que tout est démarré

```bash
curl http://localhost:8080/api/v1/actuator/health
# → {"status":"UP"}
```

---

## 2. Story 6.2 — Onboarding voyageur (compte bancaire)

### Objectif

Le voyageur connecte son RIB/compte bancaire via Stripe Express pour pouvoir recevoir des paiements.

### Flux complet

```
App Flutter → POST /payments/connect/account
           → POST /payments/connect/onboarding-link
           → Ouvre WebView vers URL Stripe
           → Voyageur remplit le formulaire Stripe (identité + RIB)
           → Stripe envoie webhook account.updated (charges_enabled=true)
           → user.stripe_onboarded = true en base
```

### Tester via l'app

1. Connexion avec un compte **voyageur** dans l'app
2. Aller dans **Profil → Recevoir mes paiements**
3. Cliquer **"Connecter mon compte bancaire"**
4. Sur la page Stripe, utiliser les données de test :
   - **Numéro de compte (IBAN test)** : `FR7630006000011234567890189`
   - **Remplir les données personnelles demandées** (prénom, nom, date de naissance)
   - Pour "Représentant légal" : cocher "Je suis le représentant légal"
5. Soumettre → retour dans l'app → "Paiements activés ✓"

### Vérifier en base

```sql
SELECT id, email, stripe_account_id, stripe_onboarded
FROM users
WHERE roles @> '["ROLE_TRAVELER"]'::jsonb
  OR id IN (SELECT DISTINCT user_id FROM user_roles WHERE role = 'ROLE_TRAVELER');
```

**Attendu :** `stripe_onboarded = true`, `stripe_account_id = acct_xxx`

### Tester via cURL (sans app)

```bash
# 1. Créer le compte Express
curl -X POST http://localhost:8080/api/v1/payments/connect/account \
  -H "Authorization: Bearer <FIREBASE_TOKEN_VOYAGEUR>"

# 2. Générer le lien d'onboarding
curl -X POST http://localhost:8080/api/v1/payments/connect/onboarding-link \
  -H "Authorization: Bearer <FIREBASE_TOKEN_VOYAGEUR>"
# → { "url": "https://connect.stripe.com/setup/..." }
# → Ouvrir cette URL dans un navigateur et remplir avec données test Stripe
```

### Webhook à surveiller dans le terminal Stripe CLI

```
--> account.updated [evt_xxx]
<-- POST /api/v1/payments/webhook [200]
```

Et dans les logs backend :
```
INFO  PaymentService : Stripe onboarding complete for user <id>
```

---

## 3. Story 6.3 — Paiement expéditeur (escrow)

### Objectif

L'expéditeur paie son envoi via Stripe. L'argent est **bloqué** (escrow) — ni capturé ni transféré. Le voyageur n'est payé qu'à la confirmation de livraison.

### Prérequis

- Bid avec `status = ACCEPTED`
- Voyageur du trajet a `stripe_onboarded = true`

### Flux complet

```
1. Expéditeur ouvre détail du bid → bouton "Payer mon envoi"
2. App Flutter : POST /payments { bidId }
   → Backend crée PaymentIntent (capture_method: MANUAL)
   → Retourne clientSecret
3. Flutter ouvre la Stripe Payment Sheet avec clientSecret
4. Expéditeur saisit sa carte
   → Carte de test : 4242 4242 4242 4242
   → Date : n'importe quelle date future (ex: 12/28)
   → CVC : n'importe quel 3 chiffres (ex: 123)
   → Code postal : n'importe quel (ex: 75001)
5. Valider → Stripe autorise la carte
6. Webhook payment_intent.amount_capturable_updated reçu
   → payment.status = ESCROW en base
7. Flutter affiche "Paiement sécurisé — X €" (badge vert)
```

### Cartes de test Stripe

| Carte | Comportement |
|-------|-------------|
| `4242 4242 4242 4242` | Paiement réussi |
| `4000 0000 0000 9995` | Carte refusée (fonds insuffisants) |
| `4000 0027 6000 3184` | Authentification 3D Secure requise |

### Tester via cURL

```bash
# Récupérer le Firebase token de l'expéditeur (depuis l'app ou Firebase Console)
TOKEN_SENDER="<FIREBASE_TOKEN_SENDER>"
BID_ID="<UUID_DU_BID_ACCEPTE>"

curl -X POST http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN_SENDER" \
  -H "Content-Type: application/json" \
  -d '{"bidId": "'$BID_ID'"}'
```

**Réponse attendue :**
```json
{
  "id": "uuid-payment",
  "bidId": "uuid-bid",
  "clientSecret": "pi_xxx_secret_xxx",
  "amount": 72.00,
  "commissionAmount": 8.64,
  "status": "PENDING"
}
```

### Vérifier en base après paiement

```sql
SELECT id, bid_id, stripe_payment_intent_id, amount, commission_amount, status
FROM payments
WHERE bid_id = '<BID_ID>';
```

**Attendu immédiatement après POST /payments :** `status = PENDING`

**Attendu après que le webhook `payment_intent.amount_capturable_updated` arrive :**
```
status = ESCROW
```

### Vérifier le paiement dans le dashboard Stripe

1. Ouvrir [https://dashboard.stripe.com/test/payments](https://dashboard.stripe.com/test/payments)
2. Le PaymentIntent doit être en état **"Requires capture"** (pas encore capturé)
3. Le montant total s'affiche, mais les fonds ne sont pas transférés

### Statut via API

```bash
curl http://localhost:8080/api/v1/payments/bid/<BID_ID> \
  -H "Authorization: Bearer $TOKEN_SENDER"
# → { "status": "ESCROW", "amount": 72.00, ... }
```

---

## 4. Story 6.4 — Déblocage automatique après livraison

### Objectif

Quand la livraison est confirmée (Epic 7), le paiement est automatiquement capturé et transféré au voyageur. **Cette story n'a pas d'endpoint public** — elle est déclenchée par un Spring Event interne.

### Comment ça se déclenche (Epic 7)

Le `TrackingService` publie un `DeliveryConfirmedEvent(bidId)` après confirmation du destinataire. Le `DeliveryEventListener` dans `payments/` écoute et capture.

### Simuler manuellement pour test (sans Epic 7)

Tu peux publier l'event manuellement depuis un test ou un endpoint admin temporaire. En attendant Epic 7, utilise l'**endpoint admin force-release** (Story 6.5).

### Flux automatique (quand Epic 7 est implémenté)

```
TrackingService.confirmDelivery(bidId)
  → applicationEventPublisher.publishEvent(new DeliveryConfirmedEvent(bidId))
  → DeliveryEventListener.handleDeliveryConfirmed()
    → PaymentIntent.retrieve(piId).capture()
    → payment.status = RELEASED
    → payment.escrow_released_at = NOW()
    → audit_log: ESCROW_RELEASED
```

### Vérifier en base après déblocage

```sql
SELECT id, bid_id, status, escrow_released_at
FROM payments
WHERE status = 'RELEASED';
```

### Vérifier dans le dashboard Stripe

Dans [https://dashboard.stripe.com/test/payments](https://dashboard.stripe.com/test/payments), le PaymentIntent passe de **"Requires capture"** à **"Succeeded"** et un transfer vers le compte connecté apparaît dans **"Connect → Transfers"**.

---

## 5. Story 6.5 — Panel admin : déblocage manuel J+48

### Objectif

Si le destinataire n'a pas confirmé la réception 48h après le paiement, un opérateur admin peut débloquer le paiement manuellement.

### Comment ça fonctionne

**1. Détection automatique (scheduler horaire)**

Le `EscrowScheduler` tourne toutes les heures. Il cherche les paiements `status = ESCROW` dont `created_at < NOW() - 48h`. Pour chacun, il crée une `AdminAlert`.

En développement, on peut voir les alertes directement en base :

```sql
-- Voir toutes les alertes non résolues
SELECT id, type, payload, resolved, created_at
FROM admin_alerts
WHERE resolved = false
ORDER BY created_at DESC;
```

**2. Déblocage via API admin**

L'admin doit avoir le rôle `ROLE_ADMIN` dans Firebase (claims custom).

```bash
TOKEN_ADMIN="<FIREBASE_TOKEN_ADMIN>"
PAYMENT_ID="<UUID_DU_PAYMENT>"

curl -X POST http://localhost:8080/api/v1/admin/payments/$PAYMENT_ID/force-release \
  -H "Authorization: Bearer $TOKEN_ADMIN"
```

**Réponse attendue :**
```json
{
  "id": "uuid-payment",
  "bidId": "uuid-bid",
  "clientSecret": null,
  "amount": 72.00,
  "commissionAmount": 8.64,
  "status": "RELEASED"
}
```

**Réponses d'erreur possibles :**

| Statut | Code | Cause |
|--------|------|-------|
| `404` | `payment-not-found` | Payment ID invalide |
| `422` | `payment-not-in-escrow` | Payment pas en statut ESCROW (déjà released, refunded, etc.) |
| `403` | — | Token ne contient pas `ROLE_ADMIN` |
| `500` | `stripe-capture-failed` | Erreur Stripe (compte connecté invalide, etc.) |

### Scénario de test complet

```bash
# 1. Créer un paiement escrow normalement (Story 6.3)
# 2. Attendre ou simuler 48h (modifier created_at en base pour test)

docker exec dony_db psql -U dony -d dony_dev -c \
  "UPDATE payments SET created_at = NOW() - INTERVAL '49 hours' WHERE status = 'ESCROW';"

# 3. Déclencher le scheduler manuellement OU attendre l'heure ronde
# En dev on peut appeler directement le scheduler via un test ou vérifier la base

# 4. Vérifier l'alerte créée
docker exec dony_db psql -U dony -d dony_dev -c \
  "SELECT * FROM admin_alerts WHERE type = 'ESCROW_J48_TIMEOUT';"

# 5. Force-release via API admin
curl -X POST http://localhost:8080/api/v1/admin/payments/<PAYMENT_ID>/force-release \
  -H "Authorization: Bearer $TOKEN_ADMIN"

# 6. Vérifier que l'alerte est résolue
docker exec dony_db psql -U dony -d dony_dev -c \
  "SELECT resolved FROM admin_alerts WHERE type = 'ESCROW_J48_TIMEOUT';"
# → resolved = true

# 7. Vérifier le payment
docker exec dony_db psql -U dony -d dony_dev -c \
  "SELECT status, escrow_released_at FROM payments WHERE id = '<PAYMENT_ID>';"
# → status = RELEASED, escrow_released_at = <timestamp>
```

### Donner le rôle ADMIN à un utilisateur (test)

Dans la Firebase Console :
1. Aller dans **Authentication → Users**
2. Sélectionner l'utilisateur
3. Aller dans **Custom claims** et définir : `{"ROLE_ADMIN": true}`

Ou via Firebase Admin SDK :
```js
admin.auth().setCustomUserClaims(uid, { ROLE_ADMIN: true });
```

### Audit trail

Chaque force-release est tracé dans `audit_log` :

```sql
SELECT entity_type, entity_id, action, actor_id, payload, created_at
FROM audit_log
WHERE action = 'ESCROW_FORCE_RELEASED'
ORDER BY created_at DESC;
```

**Format du payload :**
```json
{
  "paymentId": "uuid",
  "bidId": "uuid",
  "piId": "pi_xxx",
  "amount": "72.00"
}
```

---

## 6. Story 6.7 — Remboursement automatique sur annulation

### Objectif

Quand un voyageur annule son trajet, l'expéditeur qui avait déjà payé est **automatiquement remboursé** sur sa carte d'origine. Aucune action manuelle requise.

### Flux complet

```
Voyageur → POST /cancellations { announcementId, reason }
  → CancellationService annule tous les bids ACCEPTED de ce trajet
  → Publie TripCancelledEvent(announcementId, travelerId, affectedSenderIds, reason, affectedBidIds)
  → TripCancelledEventListener reçoit l'event (async)
    → Pour chaque bidId dans affectedBidIds :
        - Cherche le payment par bidId
        - Si status = ESCROW → Refund.create(paymentIntent: piId)
        - payment.status = REFUNDED
        - audit_log: PAYMENT_REFUNDED
  → Stripe envoie webhook charge.refunded
    → handleChargeRefunded() — double sécurité idempotente
```

### Cas couverts et non couverts

| Statut du paiement au moment de l'annulation | Comportement |
|----------------------------------------------|-------------|
| `ESCROW` | ✅ Remboursement Stripe automatique |
| `PENDING` | ✅ Aucun remboursement nécessaire (carte pas encore débitée) |
| `RELEASED` | ✅ Aucun remboursement (livraison déjà confirmée — litige si contesté) |
| `FAILED` | ✅ Aucun remboursement (paiement échoué) |
| `REFUNDED` | ✅ Aucun remboursement (déjà remboursé) |
| Aucun paiement | ✅ Skippe silencieusement |

### Tester le remboursement

```bash
# 1. S'assurer qu'un paiement est en status ESCROW pour un bid ACCEPTED
docker exec dony_db psql -U dony -d dony_dev -c \
  "SELECT p.id, p.status, b.id as bid_id, b.announcement_id
   FROM payments p JOIN bids b ON p.bid_id = b.id
   WHERE p.status = 'ESCROW';"

# 2. Annuler le trajet via l'API (avec le token du voyageur propriétaire du trajet)
TOKEN_VOYAGEUR="<FIREBASE_TOKEN_VOYAGEUR>"
ANNOUNCEMENT_ID="<UUID_DE_L_ANNONCE>"

curl -X POST http://localhost:8080/api/v1/cancellations \
  -H "Authorization: Bearer $TOKEN_VOYAGEUR" \
  -H "Content-Type: application/json" \
  -d '{"announcementId": "'$ANNOUNCEMENT_ID'", "reason": "Empêchement personnel"}'

# 3. Vérifier le statut du paiement (dans les secondes qui suivent)
docker exec dony_db psql -U dony -d dony_dev -c \
  "SELECT status FROM payments WHERE bid_id = '<BID_ID>';"
# → REFUNDED

# 4. Vérifier le webhook dans le terminal Stripe CLI
# → charge.refunded [evt_xxx] → 200
```

### Vérifier le remboursement dans Stripe Dashboard

1. Ouvrir [https://dashboard.stripe.com/test/payments](https://dashboard.stripe.com/test/payments)
2. Le PaymentIntent passe de **"Requires capture"** à **"Refunded"**
3. Dans les détails du paiement → section **"Refunds"** : un remboursement de 100% doit apparaître

**Délai de remboursement Stripe (production) :** 5 à 10 jours ouvrés pour que les fonds arrivent sur le compte de l'expéditeur. En mode test, c'est instantané.

### Audit trail du remboursement

```sql
SELECT entity_type, entity_id, action, actor_id, payload, created_at
FROM audit_log
WHERE action = 'PAYMENT_REFUNDED'
ORDER BY created_at DESC;
```

**Format du payload :**
```json
{
  "bidId": "uuid",
  "piId": "pi_xxx",
  "amount": "72.00",
  "reason": "trip_cancelled"
}
```

### Cas de paiement PENDING (carte pas encore débitée)

Si l'expéditeur a initié le paiement mais n'a pas encore validé sa carte (Payment Sheet ouverte ou abandonnée), le paiement est en statut `PENDING`. Dans ce cas :
- Stripe ne rembourse pas (aucun montant n'a été débité)
- Le PaymentIntent Stripe expirera automatiquement après 24h
- `TripCancelledEventListener` détecte `status != ESCROW` et skip → **aucune action**

---

## 7. Vérifications en base de données

### Connexion à la base

```bash
docker exec -it dony_db psql -U dony -d dony_dev
```

### Requêtes utiles

**Vue complète de l'état des paiements :**
```sql
SELECT
  p.id AS payment_id,
  p.status,
  p.amount,
  p.commission_amount,
  p.stripe_payment_intent_id,
  p.escrow_released_at,
  b.id AS bid_id,
  b.status AS bid_status,
  u_sender.email AS sender_email,
  u_traveler.email AS traveler_email,
  u_traveler.stripe_onboarded,
  p.created_at
FROM payments p
JOIN bids b ON p.bid_id = b.id
JOIN announcements a ON b.announcement_id = a.id
JOIN users u_sender ON b.sender_id = u_sender.id
JOIN users u_traveler ON a.traveler_id = u_traveler.id
ORDER BY p.created_at DESC;
```

**Alertes admin actives (J+48) :**
```sql
SELECT id, type, payload, resolved, created_at
FROM admin_alerts
WHERE resolved = false
ORDER BY created_at DESC;
```

**Historique des actions paiement (audit_log) :**
```sql
SELECT action, entity_id, payload, created_at
FROM audit_log
WHERE entity_type = 'PAYMENT'
ORDER BY created_at DESC;
```

**Actions disponibles dans l'audit_log pour les paiements :**

| Action | Déclenchée par |
|--------|---------------|
| `PAYMENT_ESCROW_CREATED` | `POST /payments` — PaymentIntent créé |
| `PAYMENT_ESCROW_ACTIVE` | Webhook `payment_intent.amount_capturable_updated` |
| `PAYMENT_FAILED` | Webhook `payment_intent.payment_failed` |
| `ESCROW_RELEASED` | `DeliveryEventListener` (Story 6.4) |
| `ESCROW_FORCE_RELEASED` | `POST /admin/payments/{id}/force-release` (Story 6.5) |
| `PAYMENT_REFUNDED` | `TripCancelledEventListener` (Story 6.7) |
| `STRIPE_ACCOUNT_CREATED` | `POST /payments/connect/account` (Story 6.2) |
| `STRIPE_ONBOARDING_COMPLETE` | Webhook `account.updated` (Story 6.2) |

---

## 8. Erreurs fréquentes et solutions

### `application_fee_amount` sans `transfer_data[destination]`

**Cause :** Le voyageur a `stripe_onboarded = true` en base mais `stripe_account_id` est `NULL`.

**Solution :**
```sql
-- Vérifier
SELECT email, stripe_account_id, stripe_onboarded FROM users WHERE stripe_onboarded = true;

-- Corriger : demander au voyageur de refaire l'onboarding
UPDATE users SET stripe_onboarded = false WHERE stripe_account_id IS NULL;
```

### `insufficient_capabilities_for_transfer`

**Cause :** Le compte Express du voyageur n'a pas la capacité `transfers` activée (onboarding incomplet ou en attente de validation Stripe).

**Solution :**
1. Dans le Stripe Dashboard Test → **Connect → Accounts**
2. Trouver le compte `acct_xxx` du voyageur
3. Vérifier que `charges_enabled = true` et `transfers = active`
4. Si `pending` → le voyageur doit compléter le formulaire Stripe (rouvrir le lien d'onboarding)

### `PaymentSheet cannot set up` (app Flutter)

**Cause :** Le `clientSecret` correspond à un PaymentIntent déjà en statut `requires_capture` (déjà autorisé). La Payment Sheet ne peut pas initialiser un intent déjà autorisé.

**Solution :** L'API retourne maintenant une erreur 409 dans ce cas. Flutter intercepte et affiche le badge "Paiement sécurisé" plutôt que d'ouvrir la Payment Sheet.

### Port 8080 déjà utilisé au redémarrage

```bash
lsof -ti:8080 | xargs kill -9
```

### Webhook non reçu (status PENDING reste PENDING)

**Causes possibles :**
1. Stripe CLI non démarré → relancer `stripe listen --forward-to localhost:8080/api/v1/payments/webhook`
2. `STRIPE_WEBHOOK_SECRET` ne correspond pas au `whsec_` affiché par le CLI → relancer le backend avec le bon secret
3. Backend non démarré sur le port 8080

**Vérification :**
```bash
# Dans le terminal du Stripe CLI, les events doivent apparaître ainsi :
# --> payment_intent.amount_capturable_updated [evt_xxx]
# <-- POST /api/v1/payments/webhook [200]

# Si le status est 400 → problème de signature
# Si le status est 500 → erreur dans le handler (voir les logs backend)
```

### Remboursement non déclenché après annulation

**Causes possibles :**
1. Le paiement est en statut `PENDING` (pas encore autorisé) → normal, pas de remboursement nécessaire
2. `TripCancelledEventListener` a loggé une `StripeException` → vérifier les logs backend

**Vérification dans les logs backend :**
```
INFO  TripCancelledEventListener : TripCancelledEvent received for announcement=xxx, processing N bid(s) for refund
INFO  TripCancelledEventListener : Refund issued for payment xxx (bid=xxx, PI=pi_xxx)
```

**Si `StripeException` dans les logs :**
- Stripe Dashboard → [https://dashboard.stripe.com/test/payments](https://dashboard.stripe.com/test/payments) → vérifier si le PaymentIntent est dans un état remboursable
- Un intent `requires_capture` peut être annulé via `pi.cancel()` — le remboursement via `Refund.create()` fonctionne aussi sur les intents authorized mais non capturés

### `Paiement déjà effectué pour cette demande` (HTTP 409)

**Cause :** Le paiement est déjà en statut `ESCROW` ou `RELEASED`.

**Solution :** L'expéditeur n'a pas besoin de payer à nouveau. Le badge "Paiement sécurisé" doit s'afficher dans l'app. Si l'app redemande un paiement, vider le cache ou recharger l'écran.
