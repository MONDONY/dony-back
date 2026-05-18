# Checklist Stripe — Passage en production

## 1. Clés API live
- [ ] Récupérer la **clé secrète live** dans Dashboard Stripe → Développeurs → Clés API
- [ ] Définir la variable d'env : `STRIPE_SECRET_KEY=sk_live_...`

## 2. Endpoint webhook — Paiements & Connect
- [ ] Dashboard → Développeurs → Webhooks → Ajouter un endpoint
  - URL : `https://<votre-domaine>/api/v1/payments/webhook`
  - Type : **Compte + Événements Connect** ← obligatoire pour account.*, transfer.*, payout.*
  - Événements à cocher (20) :
    - `account.updated`
    - `account.application.deauthorized`
    - `capability.updated`
    - `charge.dispute.created`
    - `charge.dispute.closed`
    - `charge.dispute.funds_withdrawn`
    - `charge.dispute.funds_reinstated`
    - `charge.refunded`
    - `charge.refund.updated`
    - `payment_intent.amount_capturable_updated`
    - `payment_intent.canceled`
    - `payment_intent.payment_failed`
    - `payment_intent.succeeded`
    - `payment_method.detached`
    - `payout.failed`
    - `payout.paid`
    - `radar.early_fraud_warning.created`
    - `setup_intent.succeeded`
    - `transfer.created`
    - `transfer.reversed`
    - `transfer.updated`
- [ ] Copier le **signing secret** → `STRIPE_WEBHOOK_PAYMENTS_SECRET=whsec_live_...`

## 3. Endpoint webhook — KYC (Stripe Identity)
- [ ] Dashboard → Développeurs → Webhooks → Ajouter un endpoint
  - URL : `https://<votre-domaine>/api/v1/kyc/webhook`
  - Type : **Compte uniquement**
  - Événements à cocher (3) :
    - `identity.verification_session.verified`
    - `identity.verification_session.requires_input`
    - `identity.verification_session.canceled`
- [ ] Copier le **signing secret** → `STRIPE_WEBHOOK_KYC_SECRET=whsec_live_...`

## 4. Stripe Connect
- [ ] Vérifier que le MCC est `4215` (Transport international)
- [ ] Vérifier le branding de la plateforme (logo, couleurs) dans Settings → Branding
- [ ] Capabilities demandées à la création d'un compte Express : `card_payments` + `transfers`
- [ ] Vérifier les pays autorisés pour les comptes Connect (FR, SN, CI, ML, CM, GN, BJ, TG, NE)

## 5. Stripe Identity
- [ ] Vérifier les pays autorisés pour la vérification KYC dans le dashboard Identity
- [ ] S'assurer que les documents acceptés correspondent aux pays de la diaspora cible

## 6. Variables d'environnement à déployer
```
STRIPE_SECRET_KEY=sk_live_...
STRIPE_WEBHOOK_PAYMENTS_SECRET=whsec_live_...
STRIPE_WEBHOOK_KYC_SECRET=whsec_live_...
```

## 7. Vérifications post-déploiement
- [ ] Tester un webhook depuis le dashboard Stripe → l'event apparaît dans `stripe_event_inbox`
  ```sql
  SELECT event_id, event_type, status FROM stripe_event_inbox ORDER BY received_at DESC LIMIT 5;
  ```
- [ ] Vérifier que le scheduler traite les events (logs `Stripe event scheduler processed N events`)
- [ ] Tester `GET /api/v1/admin/chargebacks` avec un token admin → 200

## 8. Note Connect — events de comptes connectés
Les events `transfer.*`, `payout.*`, `account.*`, `capability.*` sont émis par les
**comptes connectés**, pas par le compte plateforme. L'endpoint `/payments/webhook` doit
être configuré en mode **"Compte + Événements Connect"** dans le dashboard pour les recevoir.
