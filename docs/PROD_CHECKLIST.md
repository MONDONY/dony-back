# Checklist Passage en Production — dony Backend

> Ce document couvre uniquement les **variables d'application et flags métier** à configurer/activer avant de passer en prod.
> Pour l'infrastructure (VPS, Docker, Nginx, SSL, CI/CD), voir `DEPLOYMENT_GUIDE.md`.

---

## 1. Flags métier à activer

Ces flags sont désactivés en dev et en test pour ne pas bloquer le développement. Ils doivent être `true` en prod.

| Flag | Fichier à éditer | Valeur dev | Valeur prod | Effet |
|---|---|---|---|---|
| `dony.kyc.enforce` | `application.yml` ou variable d'env | `false` | `true` | Bloque la création de bid et d'annonce sans KYC vérifié |
| `dony.stripe.enforce` | `application.yml` ou variable d'env | `false` | `true` | Bloque la création d'annonce sans compte bancaire Stripe configuré |

**Comment faire :**

Option A — dans le fichier `application.yml` (profil prod) :
```yaml
dony:
  kyc:
    enforce: true
  stripe:
    enforce: true
```

Option B — via variable d'environnement dans le `.env` du serveur (recommandé) :
```bash
DONY_KYC_ENFORCE=true
DONY_STRIPE_ENFORCE=true
```

---

## 2. Secrets à générer et configurer

### `INTERNAL_SHARED_SECRET`

Utilisé pour sécuriser l'endpoint `/internal/messaging/notify` appelé par les Firebase Functions.

- En dev : valeur par défaut `local-dev-secret-change-me` (non sécurisé)
- En prod : **obligatoirement une valeur aléatoire forte**

```bash
# Générer la valeur
openssl rand -hex 32
# Exemple: a3f9d2e1b4c7...

# L'ajouter dans le .env du serveur
INTERNAL_SHARED_SECRET=<valeur générée>
```

La **même valeur** doit être configurée dans tes Firebase Functions (variable d'environnement `INTERNAL_SHARED_SECRET`) pour que les appels soient autorisés.

### `STRIPE_SECRET_KEY`

- Dev : clé test Stripe (`sk_test_...`)
- Prod : clé live Stripe (`sk_live_...`) — Stripe Dashboard → Developers → API Keys

### `STRIPE_WEBHOOK_SECRET`

- Dev : secret du webhook de test
- Prod : secret du webhook live — Stripe Dashboard → Webhooks → ton endpoint → Signing secret

### `STRIPE_WEBHOOK_SECRET` pour le KYC (Stripe Identity)

Stripe Identity utilise un webhook séparé. Vérifier que `stripe.webhook-secret` dans ta config correspond au secret du webhook Identity en prod.

---

## 3. Webhooks Stripe à déclarer en prod

Aller dans Stripe Dashboard → Developers → Webhooks → **Add endpoint**.

### Webhook paiements

- **URL :** `https://api.tondomaine.com/api/v1/payments/webhook`
- **Événements à écouter :**
  - `payment_intent.succeeded`
  - `payment_intent.payment_failed`
  - `account.updated`
  - `charge.updated`

### Webhook KYC (Stripe Identity)

- **URL :** `https://api.tondomaine.com/api/v1/kyc/webhook`
- **Événements à écouter :**
  - `identity.verification_session.verified`
  - `identity.verification_session.requires_input`
  - `identity.verification_session.canceled`

---

## 4. Migrations Flyway à appliquer

Ces migrations sont sur la branche `security/fix-and-idempotency-review` et doivent être appliquées avant le premier démarrage en prod.

| Migration | Ce qu'elle crée/modifie | Risque |
|---|---|---|
| `V46__kyc_cleanup.sql` | Aligne les statuts KYC, supprime colonnes mortes | Faible — données de dev uniquement |
| `V47__add_kyc_status_not_started.sql` | Nouveau statut `NOT_STARTED`, reclassifie les PENDING sans session | Faible — lecture seule sur les utilisateurs existants |
| `V48__idempotency_constraints.sql` | Contraintes UNIQUE, table `processed_stripe_events`, colonne `payments.captured_at` | Moyen — `ADD CONSTRAINT` peut échouer si doublons existants en prod |
| `V49__add_user_version.sql` | Colonne `version` sur `users` | Faible — `ADD COLUMN` avec valeur par défaut |

**Point d'attention sur V48 :** Avant de déployer, vérifier qu'il n'y a pas de doublons sur `users.stripe_account_id` en prod :
```sql
SELECT stripe_account_id, COUNT(*)
FROM users
WHERE stripe_account_id IS NOT NULL
GROUP BY stripe_account_id
HAVING COUNT(*) > 1;
```
Si des doublons existent, les nettoyer avant le déploiement.

---

## 5. Variables Firebase

| Variable | Valeur prod |
|---|---|
| `firebase.service-account-path` | Chemin vers le JSON service account Firebase prod |
| Firebase Project ID | Projet Firebase prod (pas le projet de dev) |

Vérifier que le projet Firebase en prod a bien **Phone Authentication** activé dans Authentication → Sign-in method.

---

## 6. Vérifications sécurité avant mise en ligne

- [ ] `dony.kyc.enforce=true` — aucun bid/annonce sans KYC
- [ ] `dony.stripe.enforce=true` — aucune annonce sans compte bancaire configuré
- [ ] `INTERNAL_SHARED_SECRET` généré avec `openssl rand -hex 32` et configuré dans les Firebase Functions
- [ ] Clés Stripe live (`sk_live_...`) en place — pas de clés test en prod
- [ ] Webhooks Stripe (paiements + KYC) déclarés sur l'URL de prod avec les bons events
- [ ] Signature des webhooks validée (variable `STRIPE_WEBHOOK_SECRET` correcte)
- [ ] Firebase service account du projet prod (pas du projet dev)
- [ ] Migrations V46 → V49 appliquées sans erreur
- [ ] Pas de doublons sur `users.stripe_account_id` (vérification V48)
- [ ] Sentry DSN prod configuré (`sentry.dsn`)
- [ ] `./mvnw test` → 0 rouge avant de déployer
- [ ] **Vérifier les capacités Stripe Connect des premiers voyageurs** — dans Stripe Dashboard → Comptes connectés → compte du voyageur → section "Capacités", confirmer que `card_payments` ET `transfers` sont bien `Actif`. Si `card_payments` est inactif, le paiement échoue avec l'erreur "on_behalf_of sans card_payments". En mode live, l'onboarding complet (vrai RIB + identité) active ces capacités automatiquement — mais vérifier pour les premiers comptes.

---

## 7. Test de fumée après déploiement

Vérifier ces endpoints dans l'ordre après le premier démarrage en prod :

```bash
BASE=https://api.tondomaine.com/api/v1

# 1. Santé de l'application
curl $BASE/actuator/health

# 2. Créer une annonce sans KYC → doit retourner 403 kyc-not-verified
curl -X POST $BASE/announcements \
  -H "Authorization: Bearer <token-sans-kyc>" \
  -H "Content-Type: application/json" \
  -d '{...}'

# 3. Créer une annonce avec KYC mais sans Stripe → doit retourner 403 stripe-onboarding-incomplete
curl -X POST $BASE/announcements \
  -H "Authorization: Bearer <token-kyc-ok-stripe-nok>" \
  -H "Content-Type: application/json" \
  -d '{...}'

# 4. Rejouer un webhook Stripe (depuis le Dashboard → Send test event) → doit être ignoré silencieusement
```

---

**Dernière mise à jour :** 2026-05-06
**Branche de référence :** `security/fix-and-idempotency-review`
