# Story 6.2 — Onboarding compte Stripe Connect voyageur (Backend)

**Date:** 2026-04-25
**Status:** ✅ Complète

---

## Résumé

Un voyageur KYC-vérifié peut connecter son compte bancaire via Stripe Express. Le backend crée le compte, génère le lien d'onboarding WebView, et écoute le webhook `account.updated` pour valider automatiquement quand Stripe confirme l'éligibilité aux paiements.

---

## Fichiers créés

- `src/main/resources/db/migration/V18__users_add_stripe_fields.sql` — colonnes `stripe_account_id` et `stripe_onboarded` sur la table `users`
- `payments/PaymentController.java` — 3 endpoints REST
- `payments/PaymentService.java` — logique métier onboarding + webhook
- `payments/dto/ConnectAccountResponse.java` — réponse création de compte
- `payments/dto/OnboardingLinkResponse.java` — réponse lien onboarding

## Fichiers modifiés

- `auth/UserEntity.java` — champs `stripeAccountId` + `stripeOnboarded` + getters/setters
- `auth/UserRepository.java` — `findByStripeAccountId()` pour le webhook
- `config/SecurityConfig.java` — `/payments/webhook` ajouté aux endpoints publics

---

## Comment ça fonctionne

### Flux complet (vue voyageur)

```
1. Voyageur appuie "Connecter mon compte"
        ↓
2. POST /payments/connect/account
   → Stripe crée un Express Account (acct_...)
   → UserEntity.stripeAccountId = acct_...
   → audit_log: STRIPE_ACCOUNT_CREATED
        ↓
3. POST /payments/connect/onboarding-link
   → Stripe génère une AccountLink (expire après usage)
   → Retourne l'URL https://connect.stripe.com/...
        ↓
4. Flutter ouvre la WebView avec cette URL
   → Le voyageur complète son KYC Stripe (pièce d'identité, RIB)
   → Stripe redirige vers https://dony.app/payments/onboarding/return
        ↓
5. Webhook account.updated reçu (charges_enabled = true)
   → UserEntity.stripeOnboarded = true
   → audit_log: STRIPE_ONBOARDING_COMPLETE
```

### Points d'entrée API

- `POST /api/v1/payments/connect/account` — `ROLE_TRAVELER` requis. Idempotent : si `stripeAccountId` existe déjà, retourne l'existant sans recréer.
- `POST /api/v1/payments/connect/onboarding-link` — `ROLE_TRAVELER` requis. Génère un nouveau lien à chaque appel (les liens Stripe expirent après usage unique). Lève `409 CONFLICT` si `stripeAccountId` est null.
- `POST /api/v1/payments/webhook` — Public (pas d'auth Firebase). Signature Stripe validée dans `PaymentService.handleWebhook()` via `Webhook.constructEvent()`.

### Entités JPA impliquées

- `UserEntity` → table `users` — nouveaux champs :
  - `stripe_account_id VARCHAR(64)` — ID Stripe Express du voyageur
  - `stripe_onboarded BOOLEAN DEFAULT FALSE` — true quand `charges_enabled = true`

### Logique métier critique

**Idempotence de `createConnectAccount`** : si l'utilisateur rappelle l'endpoint (retour de WebView, bug réseau), on retourne le compte existant au lieu d'en créer un nouveau. Stripe ne permet qu'un compte Express par email.

**Validation webhook** : `Webhook.constructEvent()` lève `SignatureVerificationException` si la signature est invalide → réponse HTTP 400. Ne jamais traiter un webhook sans cette validation (risque de fraude).

**Guard `charges_enabled`** : on ne passe `stripeOnboarded = true` que si `account.getChargesEnabled() == true`. Un `account.updated` peut arriver plusieurs fois pendant l'onboarding (étapes intermédiaires) — seul le dernier avec `charges_enabled = true` compte.

**Webhook Secret** : injecté via `@Qualifier("stripeWebhookSecret")` depuis le bean `StripeConfig.stripeWebhookSecret()`. Jamais hardcodé. En dev, fourni via `STRIPE_WEBHOOK_SECRET` env var.

### Pièges et points d'attention

- **AccountLink à usage unique** : une fois que le voyageur ouvre l'URL, elle ne peut pas être réutilisée. Si le voyageur revient en arrière sans terminer, il faut rappeler `/onboarding-link` pour générer une nouvelle URL. Le flow Flutter gère ça avec le callback `onReturn`.
- **`refresh_url`** : Stripe redirige vers cette URL si le lien a expiré ou si l'utilisateur actualise. Le WebView l'intercepte et déclenche un nouveau `PaymentConnectAccountRequested`.
- **Délai webhook** : Stripe peut envoyer `account.updated` avec `charges_enabled = true` quelques secondes à plusieurs minutes après la fin de l'onboarding. La vue Flutter affiche un état "En cours de vérification..." pendant ce temps.
- **Pas de `@Transactional` sur `handleWebhook`** : le webhook peut arriver plusieurs fois (retry Stripe). Le guard `if (!user.isStripeOnboarded())` protège contre les doubles écritures.

---

## Critères d'acceptation couverts

- [x] **Given** un voyageur KYC-vérifié sans compte Stripe Connect **When** il accède à l'écran "Recevoir mes paiements" **Then** l'URL d'onboarding Stripe s'ouvre dans la WebView → `POST /connect/account` + `POST /connect/onboarding-link`
- [x] **And** après complétion, `User.stripeOnboarded = true` est mis à jour via webhook → `handleAccountUpdated()` sur event `account.updated`
- [x] **Given** un voyageur ayant complété l'onboarding **When** il consulte l'écran **Then** un indicateur "Paiements activés ✓" est affiché → state `PaymentOnboardingComplete` côté Flutter

## Décisions techniques

**Stripe Express plutôt que Custom** : Stripe gère l'onboarding, le KYC et la conformité. En Custom, dony serait responsable légalement des vérifications d'identité des voyageurs — trop risqué pour le MVP. Voir spike 6.1 pour le détail.

**`@Qualifier` pour le webhook secret** : plutôt qu'un `@Value` direct dans `PaymentService`, on passe par le bean `stripeWebhookSecret()` de `StripeConfig`. Cela facilite les tests (le bean peut être mocké) et centralise la config Stripe.
