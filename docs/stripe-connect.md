# Stripe Connect — Architecture & Notes d'intégration

**Dernière mise à jour :** 2026-05-05 (PR 5 — migration `on_behalf_of`)

Ce document couvre les décisions de conception, le cycle de vie et la configuration de Stripe Connect sur la plateforme Dony. Pour le flux de paiement de bout en bout, voir [`payments-flow.md`](./payments-flow.md).

---

## 1. Pourquoi `on_behalf_of`

### Fiscal / Légal

Sans `on_behalf_of`, la **plateforme** est le marchand de référence. Le compte Stripe du voyageur reçoit des virements, mais Stripe et les réseaux de cartes attribuent la vente à Dony. C'est juridiquement incorrect : c'est le voyageur qui fournit le service de transport et qui assume la responsabilité des marchandises.

Avec `on_behalf_of=<id_compte_connect_voyageur>`, le **compte Connect du voyageur est le marchand de règlement**. Stripe déclare le voyageur comme vendeur. C'est la posture légale correcte en France et dans la plupart des juridictions d'Afrique de l'Ouest où Dony opère (Dakar, Abidjan, Bamako, Douala).

### UX

Le relevé bancaire de l'expéditeur affiche le **nom de l'entreprise du voyageur** (ou son nom pour les comptes Express individuels), et non "Dony". Cela réduit les rétrofacturations des expéditeurs qui ne reconnaissent pas un prélèvement générique de la plateforme.

### Exigence réglementaire / capacité Stripe

`on_behalf_of` requiert que le compte Connect ait la capacité **`card_payments`** activée. C'est pourquoi `AccountCreateParams` demande explicitement :

```java
.addCapability(AccountCreateParams.Capability.builder()
    .setType(AccountCreateParams.Capability.Type.CARD_PAYMENTS)
    .setRequested(true)
    .build())
.addCapability(AccountCreateParams.Capability.builder()
    .setType(AccountCreateParams.Capability.Type.TRANSFERS)
    .setRequested(true)
    .build())
```

Stripe refusera un `PaymentIntent` avec `on_behalf_of` si `card_payments` n'est pas activé sur le compte de destination. La tentative retourne une `invalid_request_error` Stripe.

---

## 2. Cycle de vie du compte Connect

### Diagramme d'états

```
                   création du premier trajet
NOT_CREATED ─────────────────────────────► PENDING_ONBOARDING
                                                  │
                           webhook account.updated │
                      (chargesEnabled=true         │
                       ET payoutsEnabled=true)     │
                                                   ▼
                                         ONBOARDING_COMPLETE
                                         (les offres peuvent être placées)

PENDING_ONBOARDING ──────────────────────────────► REJECTED
   (disabled_reason commence par "rejected.*")

PENDING_ONBOARDING ──────────────────────────────► DISABLED
   (autre disabled_reason présent)
```

### Description des statuts

| Statut | Signification | Ce qui est bloqué |
|--------|--------------|------------------|
| `NOT_CREATED` | Le voyageur n'a pas encore de compte Stripe Express | Impossible de créer des trajets |
| `PENDING_ONBOARDING` | Compte créé ; lien d'onboarding Stripe envoyé | Offres bloquées ; capture du PaymentIntent bloquée |
| `ONBOARDING_COMPLETE` | `charges_enabled` ET `payouts_enabled` sont à `true` | Rien — fonctionnalité complète |
| `REJECTED` | Stripe a rejeté le compte (`disabled_reason` commence par `rejected.*`) | Toutes les opérations de paiement bloquées ; l'utilisateur doit contacter le support |
| `DISABLED` | Une autre restriction s'applique | Capture bloquée ; `TravelerNotEligibleForPaymentException` (HTTP 422) à l'acceptation de l'offre |

### Déclencheur : `NOT_CREATED` → `PENDING_ONBOARDING`

Appelé par `ConnectService.createOrRetrieveOnboardingLink()` quand un voyageur crée son premier trajet. Le lien est généré une seule fois et transmis à l'application Flutter comme un deep link vers le flux d'onboarding Stripe Express.

### Déclencheur : `PENDING_ONBOARDING` → `ONBOARDING_COMPLETE`

Piloté par le webhook Stripe `account.updated`. Le handler appelle `deriveStripeAccountStatus(Account)`, qui retourne `ONBOARDING_COMPLETE` uniquement quand :

```java
account.getChargesEnabled() && account.getPayoutsEnabled()
```

À la **première** transition vers `ONBOARDING_COMPLETE`, un Spring event `StripeOnboardingCompletedEvent` est publié pour que les listeners en aval (ex. service de notifications) puissent réagir sans couplage direct.

### Re-vérification pré-capture

Même après l'acceptation d'une offre, `BidAcceptedEventListener` rappelle `refreshConnectAccount()` immédiatement avant `pi.capture()`. Cela protège contre les cas limites où le compte d'un voyageur est désactivé entre l'acceptation de l'offre et la capture du paiement.

---

## 3. Individuel vs Entreprise (badge PRO)

### Par défaut — `INDIVIDUAL`

Quand un voyageur crée son premier trajet et qu'aucun compte Connect n'existe, la plateforme crée un **compte Express** avec :

```java
.setBusinessType(AccountCreateParams.BusinessType.INDIVIDUAL)
```

C'est le paramètre par défaut pour les voyageurs agissant en tant que particuliers.

### Passage en PRO — `COMPANY`

Après `POST /api/v1/auth/me/upgrade-to-pro`, le flag `isProAccount` de l'utilisateur est mis à `true` et `proCompanyName` / `proSiret` sont enregistrés. Pour les **nouvelles** créations de compte Connect après ce passage en PRO, la plateforme utilise :

```java
.setBusinessType(AccountCreateParams.BusinessType.COMPANY)
```

### Limitation connue : business_type est immuable après création

Stripe **ne permet pas** de changer le `business_type` sur un compte existant. Un voyageur qui :

1. A créé au moins un trajet (compte Connect créé en `INDIVIDUAL`), puis
2. A upgradé en PRO

aura un compte Connect en décalage. L'endpoint `POST /auth/me/upgrade-to-pro` détecte ce cas et répond avec **HTTP 409 Conflict** pour éviter une incohérence silencieuse. L'utilisateur doit contacter le support pour que l'ancien compte soit clôturé et qu'un nouveau soit provisionné.

### Sémantique du badge PRO (MVP)

Le badge PRO est **purement cosmétique** en MVP. Il indique aux expéditeurs que le voyageur est une entité commerciale vérifiée, ce qui peut accroître la confiance et les réservations. Aucune capacité supplémentaire ni grille tarifaire différente ne sont débloquées par le statut PRO dans la version actuelle.

---

## 4. Événements webhook traités (`account.*`)

### Endpoint

```
POST /api/v1/payments/webhook
```

Endpoint public (aucun token Firebase requis). La signature Stripe est vérifiée à chaque requête :

```java
Webhook.constructEvent(payload, stripeSignatureHeader, stripeWebhookSecret);
```

### `account.updated`

C'est le **seul** événement au niveau du compte qui est traité. Toutes les transitions d'état du compte Connect passent par ce handler unique.

**Chaîne du handler :**

```
PaymentService.handleAccountUpdated(event)
    └─► deriveStripeAccountStatus(Account account)
            ├─ chargesEnabled && payoutsEnabled  →  ONBOARDING_COMPLETE
            ├─ disabled_reason commence par "rejected."  →  REJECTED
            ├─ disabled_reason présent (autre)  →  DISABLED
            └─ (défaut)  →  PENDING_ONBOARDING
```

**Idempotence :** `StripeOnboardingCompletedEvent` est publié **uniquement à la première transition** vers `ONBOARDING_COMPLETE`. Le handler compare le statut dérivé entrant avec le `StripeAccountStatus` persisté. Si déjà `ONBOARDING_COMPLETE`, le Spring event n'est pas republié et aucune entrée audit_log n'est écrite pour une mise à jour sans effet.

**Audit log :** chaque changement de statut (les no-ops PENDING → PENDING exclus) est écrit dans `audit_log` avec `entity_type=STRIPE_ACCOUNT` et le nouveau statut en métadonnées.

---

## 5. Référence de configuration

```yaml
# application.yml (extrait pertinent)
dony:
  stripe:
    connect:
      mcc: "4215"                          # Services de messagerie
                                           # TODO: valider vs 4214 (Services de livraison)
                                           #       avec le juridique avant la mise en prod
      product-description: "Dony — Marketplace P2P de transport de colis connectant expéditeurs de la diaspora africaine et voyageurs"
      business-url: "https://dony.app"
      return-url: "dony://stripe/onboarding/complete"
      # ^ dev : schéma URI personnalisé (intent-filter Android / URL scheme iOS)
      # ^ prod : doit migrer vers un Universal Link HTTPS (voir docs/deep-links.md)
      refresh-url: "dony://stripe/onboarding/refresh"
      # ^ même remarque — prod nécessite un Universal Link HTTPS

  commission:
    rate: 0.12    # 12 % — exposé en lecture seule via GET /api/v1/config/commission-rate
```

### Variables d'environnement (jamais dans le code)

| Variable | Rôle |
|----------|------|
| `STRIPE_SECRET_KEY` | Clé secrète de la plateforme (sk_live_… / sk_test_…) |
| `STRIPE_WEBHOOK_SECRET` | Secret de signature pour `POST /payments/webhook` |

### Note sur le MCC

Le MCC `4215` (Services de messagerie, aérien ou terrestre, fret) est utilisé pour le MVP. Le MCC `4214` (Transporteurs de fret routier et camionnage) pourrait être plus précis selon la revue juridique. Cela doit être confirmé avec l'équipe Stripe et le conseil juridique **avant la mise en production**.

### Note sur les deep links

Les `return-url` et `refresh-url` utilisent le schéma URI personnalisé `dony://`. Les politiques de l'App Store Apple et du Google Play Store, ainsi que les recommandations Stripe, exigent des **Universal Links HTTPS** (iOS) et des **Android App Links** (Android) en production. Le schéma `dony://` est acceptable uniquement pour le développement local et les tests internes (TestFlight / piste interne). Voir `docs/deep-links.md` pour le plan de migration.
