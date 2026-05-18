# Spec — Inbox Stripe asynchrone, handlers webhooks manquants & config prod

**Date :** 2026-05-17
**Statut :** Design validé — prêt pour plan d'implémentation
**Périmètre :** `dony-back/` uniquement (aucun changement Flutter)

---

## 1. Contexte & problème

Le backend expose 2 endpoints webhook Stripe (`/payments/webhook`, `/kyc/webhook`).
Aujourd'hui :

- Le webhook vérifie la signature, insère l'`event_id` dans `processed_stripe_events`
  (idempotence, insert-first), puis **traite l'event de façon synchrone** dans la
  transaction de la requête.
- `processed_stripe_events` ne stocke que `event_id` + `processed_at` : **pas de
  payload, pas de statut, pas de type** → aucun replay ni audit possible.
- Le retry en cas d'échec dépend **entièrement de Stripe** (rejeu ~3 jours puis
  abandon). Un échec dans un listener `AFTER_COMMIT` est une **perte silencieuse**
  (le webhook a déjà répondu 200).
- Sur une vingtaine d'event types pertinents pour une marketplace Connect + escrow,
  seuls 10 sont traités. **Les chargebacks bancaires ne sont pas gérés du tout.**

### Objectifs

1. **Inbox asynchrone** : ne perdre aucun event Stripe, avec retry maîtrisé côté
   backend (au-delà de la fenêtre de rejeu Stripe), payload persisté, replay possible.
2. **Handlers manquants** : chargebacks, cycle de vie des transfers/payouts Connect,
   resync des autorisations annulées, perte de compte/capacité Connect, échecs de
   remboursement, alertes fraude Radar.
3. **Gel du bid sur chargeback** : bloquer automatiquement le paiement du voyageur
   tant qu'un litige bancaire est ouvert sur le paiement correspondant.
4. **Config prod** : config Stripe live + checklist dashboard documentée.

### Non-objectifs (YAGNI)

- Pas de reversal automatique de transfer (risque de compte Connect en négatif).
- Pas de résolution automatique des chargebacks (l'admin tranche via le dashboard).
- Pas de Redis / file de messages externe — un scheduler PostgreSQL suffit (cohérent
  avec `EscrowScheduler` et `payments/cash/job/` déjà en place).
- Pas de nouveau statut dans le state machine du bid (le gel passe par un flag isolé).

---

## 2. Architecture

### 2.1 Vue d'ensemble

```
Controller (/payments/webhook | /kyc/webhook)   ── synchrone, requête HTTP ──
  → StripeWebhookIngestService.ingest(payload, sigHeader, source)
      1. Webhook.constructEvent(...)  → vérif signature (secret selon source)
      2. INSERT stripe_event_inbox (status=RECEIVED, payload brut)   [idempotent]
      3. retour → 200 immédiat
  (signature invalide → exception → 400 via GlobalExceptionHandler)

StripeEventScheduler   ── asynchrone, @Scheduled ──
  → boucle jusqu'à batchSize :
      StripeEventProcessor.processOne()   [@Transactional REQUIRES_NEW, 1 event]
        1. SELECT ... WHERE status IN (RECEIVED,FAILED) AND next_attempt_at <= now
                       ORDER BY received_at LIMIT 1 FOR UPDATE SKIP LOCKED
        2. StripeEventDispatcher.dispatch(row)
             → désérialise payload → Event
             → handler dont supports(eventType) == true
             → aucun handler → status = SKIPPED
        3. succès → status = PROCESSED, processed_at = now
           échec  → retry_count++, last_error renseigné
                    retry_count < maxRetries → status = FAILED,
                                               next_attempt_at = backoff exponentiel
                    sinon                    → status = DEAD_LETTER + alerte admin
```

**Pourquoi pas d'état `PROCESSING` ni de reaper :** chaque event est traité dans une
seule transaction qui détient le verrou de ligne (`FOR UPDATE SKIP LOCKED`) pendant
toute la durée du handler. En cas de crash, la transaction est rollback, la ligne
reste `RECEIVED`/`FAILED` et sera reprise. `SKIP LOCKED` garantit qu'une autre
instance ne traite pas la même ligne — l'inbox est correcte même en multi-instance.

### 2.2 Respect de la règle « pas d'injection cross-package »

Le dispatcher vit dans `common/stripe/` mais les handlers métier sont dans
`payments/` et `kyc/`. On utilise **l'inversion de dépendance** :

- Interface `StripeWebhookHandler` définie dans `common/stripe/`.
- Chaque feature fournit son implémentation ; le dispatcher injecte
  `List<StripeWebhookHandler>` (tous les beans de l'interface).
- Le dispatcher dépend de l'abstraction, jamais des services concrets. ✅

### 2.3 Découpage des composants

#### `common/stripe/` (nouveau)

| Composant | Rôle | Dépend de |
|---|---|---|
| `StripeWebhookSource` (enum) | `PAYMENTS`, `KYC` — sélectionne le bon signing secret | — |
| `StripeEventStatus` (enum) | `RECEIVED`, `PROCESSED`, `FAILED`, `DEAD_LETTER`, `SKIPPED` | — |
| `StripeEventInbox` (entity) | Table `stripe_event_inbox` ; PK = `event_id` (id naturel Stripe) | — |
| `StripeEventInboxRepository` | CRUD + `existsById` + claim natif `FOR UPDATE SKIP LOCKED` | entity |
| `StripeWebhookProperties` | `@ConfigurationProperties("dony.stripe.webhook")` — intervalle, maxRetries, backoff, batchSize | — |
| `StripeWebhookHandler` (interface) | `boolean supports(String eventType)` + `void handle(Event event)` | — |
| `StripeWebhookIngestService` | Vérif signature + persistance RECEIVED (idempotent). Appelé par les controllers | repo, `StripeConfig` |
| `StripeEventDispatcher` | Désérialise le payload, route vers le handler `supports()` | `List<StripeWebhookHandler>` |
| `StripeEventProcessor` | Traite 1 event dans sa propre transaction `REQUIRES_NEW` (claim + dispatch + statut/backoff) | repo, dispatcher |
| `StripeEventScheduler` | `@Scheduled(fixedDelayString=...)` — boucle d'appels à `processOne()` | processor, properties |

`StripeEventInbox` **n'étend pas `BaseEntity`** : c'est une table d'infrastructure
(pas une entité métier), la PK est l'`event_id` Stripe et il n'y a pas de soft delete.
Le pattern reprend l'actuel `ProcessedStripeEvent`.

#### `payments/` (modifié)

| Composant | Changement |
|---|---|
| `PaymentStripeWebhookHandler` (nouveau) | `implements StripeWebhookHandler` ; reprend la logique de `PaymentService.dispatchWebhookEvent` + nouveaux event types. Même package que `PaymentService` → peut appeler ses méthodes (pas de cross-package) |
| `PaymentService` | `handleWebhook(...)` supprimée (signature + idempotence migrent vers l'inbox) ; `dispatchWebhookEvent` retirée ; les handlers `handleXxx` package-private restent appelés par `PaymentStripeWebhookHandler` |
| `PaymentEntity` | + colonne `disputed` (boolean, défaut `false`) |
| `PaymentController` | `/payments/webhook` → `ingestService.ingest(payload, sig, PAYMENTS)` |
| `DeliveryEventListener` | **Garde de gel** : avant `Transfer.create` / capture, si `payment.isDisputed()` → ne rien faire, `audit_log` `DELIVERY_TRANSFER_BLOCKED_CHARGEBACK`, alerte admin, return |

#### `payments/chargeback/` (nouveau sous-package)

| Composant | Rôle |
|---|---|
| `ChargebackEntity` | `extends BaseEntity` → table `chargebacks` |
| `ChargebackStatus` (enum) | `OPEN`, `WON`, `LOST` |
| `ChargebackRepository` | CRUD + `findByStripeDisputeId` |
| `ChargebackService` | Logique `dispute.created` (crée le chargeback, passe `payment.disputed = true`), `dispute.closed` (outcome → `WON` lève le gel / `LOST` reste gelé), `funds_withdrawn`/`funds_reinstated` (audit) |
| `ChargebackController` | `GET /admin/chargebacks` (lecture seule, paginé), `@PreAuthorize("hasRole('ADMIN')")` |

#### `kyc/` (modifié)

| Composant | Changement |
|---|---|
| `KycStripeWebhookHandler` (nouveau) | `implements StripeWebhookHandler` ; reprend la logique de `KycService.processWebhook` (`identity.verification_session.*`) |
| `KycService` | `processWebhook(...)` retirée (logique déplacée dans le handler) |
| `KycController` | `/kyc/webhook` → `ingestService.ingest(payload, sig, KYC)` |

#### `config/` (modifié)

`StripeConfig` : deux beans de signing secret —
`stripePaymentsWebhookSecret`, `stripeKycWebhookSecret`. En dev/test, fallback sur un
secret partagé unique (Stripe CLI forwarde les deux endpoints avec le même secret).

#### Suppression

`common/ProcessedStripeEvent.java` + `common/ProcessedStripeEventRepository.java` sont
**supprimés** (remplacés par l'inbox). Les références dans `PaymentService` et
`KycService` sont retirées. La checklist sécurité du `CLAUDE.md` (item
« Table `processed_stripe_events` créée (V48) ») devra mentionner `stripe_event_inbox`.

---

## 3. Modèle de données

### 3.1 Migration `V84__stripe_event_inbox.sql`

```sql
CREATE TABLE stripe_event_inbox (
    event_id        VARCHAR(255) NOT NULL,
    source          VARCHAR(16)  NOT NULL,            -- PAYMENTS | KYC
    event_type      VARCHAR(128) NOT NULL,
    payload         TEXT         NOT NULL,            -- corps brut du webhook
    status          VARCHAR(16)  NOT NULL DEFAULT 'RECEIVED',
    retry_count     INT          NOT NULL DEFAULT 0,
    last_error      TEXT,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ,
    CONSTRAINT pk_stripe_event_inbox PRIMARY KEY (event_id)
);

-- index pour le claim du worker
CREATE INDEX idx_stripe_event_inbox_pending
    ON stripe_event_inbox (next_attempt_at)
    WHERE status IN ('RECEIVED', 'FAILED');

-- reprise de l'historique : les events déjà traités ne doivent pas être rejoués
INSERT INTO stripe_event_inbox (event_id, source, event_type, payload, status, received_at, processed_at)
SELECT event_id, 'PAYMENTS', 'legacy.unknown', '{}', 'PROCESSED', processed_at, processed_at
FROM processed_stripe_events
ON CONFLICT (event_id) DO NOTHING;

DROP TABLE processed_stripe_events;
```

### 3.2 Migration `V85__chargebacks.sql`

```sql
CREATE TABLE chargebacks (
    id                 UUID         NOT NULL DEFAULT gen_random_uuid(),
    stripe_dispute_id  VARCHAR(255) NOT NULL,
    stripe_charge_id   VARCHAR(255),
    payment_id         UUID,                          -- FK payments(id), nullable si non rapproché
    bid_id             UUID,
    amount             BIGINT       NOT NULL,         -- centimes
    currency           VARCHAR(8)   NOT NULL,
    reason             VARCHAR(64),
    status             VARCHAR(16)  NOT NULL DEFAULT 'OPEN',  -- OPEN | WON | LOST
    opened_at          TIMESTAMPTZ  NOT NULL,
    resolved_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ,
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT pk_chargebacks PRIMARY KEY (id),
    CONSTRAINT uq_chargebacks_dispute UNIQUE (stripe_dispute_id),
    CONSTRAINT fk_chargebacks_payment FOREIGN KEY (payment_id) REFERENCES payments(id)
);

ALTER TABLE payments
    ADD COLUMN disputed BOOLEAN NOT NULL DEFAULT FALSE;
```

`uq_chargebacks_dispute` rend la création de chargeback idempotente même si l'inbox
rejoue (`charge.dispute.created` reçu deux fois).

---

## 4. Events Stripe couverts

24 event types au total (10 existants conservés + 14 nouveaux).

### 4.1 Existants — conservés (déplacés dans les handlers)

`account.updated`, `payment_intent.amount_capturable_updated`,
`payment_intent.payment_failed`, `payment_intent.succeeded`, `charge.refunded`,
`setup_intent.succeeded`, `payment_method.detached`,
`identity.verification_session.verified`, `.requires_input`, `.canceled`.

### 4.2 Nouveaux

| Event | Handler | Action |
|---|---|---|
| `charge.dispute.created` | payments | Crée `ChargebackEntity` (`OPEN`), rapproche le `payment` via `charge_id`, **`payment.disputed = true`**, `audit_log` `STRIPE_CHARGEBACK_OPENED`, alerte admin |
| `charge.dispute.closed` | payments | `outcome=won` → chargeback `WON`, **`payment.disputed = false`** (gel levé) ; `outcome=lost` → `LOST`, reste gelé. Audit + notif |
| `charge.dispute.funds_withdrawn` / `funds_reinstated` | payments | `audit_log` du mouvement de fonds |
| `payment_intent.canceled` | payments | Si `payment` en `ESCROW`/`PENDING` → `CANCELLED`, audit (resync de l'autorisation expirée) |
| `transfer.created` | payments | `audit_log` `STRIPE_TRANSFER_CREATED` |
| `transfer.reversed` | payments | Audit + **alerte admin** (fonds repris au voyageur) |
| `transfer.updated` | payments | Audit |
| `payout.failed` | payments | Audit + **alerte admin** (versement banque voyageur échoué) |
| `payout.paid` | payments | `audit_log` |
| `account.application.deauthorized` | payments | Statut compte Connect → déconnecté, bloque les futurs paiements, notif |
| `capability.updated` | payments | Perte de la capacité `transfers` → statut compte mis à jour, notif |
| `charge.refund.updated` | payments | Si refund `status=failed` → audit + **alerte admin** |
| `radar.early_fraud_warning.created` | payments | Audit + **alerte admin** (signal fraude avant chargeback) |

**Remarque Connect :** `account.*`, `transfer.*`, `payout.*`, `capability.*` sont des
events de **comptes connectés** — l'endpoint webhook Stripe correspondant doit avoir
les events Connect activés (voir checklist prod § 8).

**Désérialisation résiliente :** comme l'actuel `KycService` (extraction du
`sessionId` depuis le JSON brut pour éviter les écarts de version d'API), les handlers
qui ont besoin de champs imbriqués lisent le **payload brut** stocké dans l'inbox
plutôt que de dépendre de la désérialisation typée du SDK.

---

## 5. Gel du bid sur chargeback

- Le gel **n'est pas un statut de bid** mais le flag `payments.disputed`, pour ne pas
  perturber le state machine du bid.
- `charge.dispute.created` → `payment.disputed = true`.
- `DeliveryEventListener` (déclenche capture / `Transfer.create` à la livraison) reçoit
  une garde en tête : `if (payment.isDisputed()) { audit + alerte admin; return; }`.
  Les fonds restent sur le compte plateforme tant que le litige est ouvert.
- Levée : `charge.dispute.closed` `outcome=won` → `disputed = false`, le flux reprend.
  `outcome=lost` → reste gelé, l'admin annule le bid via le flux d'annulation existant.

---

## 6. Configuration

### 6.1 `application.yml` (commun)

```yaml
dony:
  stripe:
    webhook:
      poll-interval: 10s        # intervalle du worker
      batch-size: 50            # events traités par tick
      max-retries: 8
      retry-backoff-base: 30s   # backoff exponentiel : base * 2^retry_count
stripe:
  webhook:
    payments-secret: ${STRIPE_WEBHOOK_PAYMENTS_SECRET:${STRIPE_WEBHOOK_SECRET:}}
    kyc-secret:      ${STRIPE_WEBHOOK_KYC_SECRET:${STRIPE_WEBHOOK_SECRET:}}
```

`StripeWebhookProperties` (record `@ConfigurationProperties`) externalise toutes les
limites — conforme à la règle « pas de valeur de seuil hardcodée ».

### 6.2 `application-dev.yml` / `application-test.yml`

- Dev : `STRIPE_WEBHOOK_SECRET` partagé (Stripe CLI).
- Test : `dony.stripe.webhook.poll-interval` court ; le scheduler est désactivable
  (`@Scheduled` piloté par propriété) pour que les tests appellent `processOne()`
  directement. `dony.kyc.enforce` / `dony.stripe.enforce` restent `false`.

### 6.3 `application-prod.yml`

Ajout du bloc `stripe.webhook.*` câblé sur les variables d'environnement live
(`STRIPE_WEBHOOK_PAYMENTS_SECRET`, `STRIPE_WEBHOOK_KYC_SECRET`). Aucune valeur en clair.

---

## 7. Tests (couverture ≥ 90 %, politique `CLAUDE.md`)

| Cible | Type | Cas couverts |
|---|---|---|
| `StripeWebhookIngestServiceTest` | unit | signature OK → RECEIVED ; signature KO → exception ; event déjà présent → pas de doublon |
| `StripeEventDispatcherTest` | unit | route vers le bon handler ; aucun handler → SKIPPED |
| `StripeEventProcessorTest` | unit | succès → PROCESSED ; échec → FAILED + backoff ; au-delà de maxRetries → DEAD_LETTER + alerte |
| `StripeEventSchedulerTest` | unit | boucle bornée par `batchSize` ; s'arrête quand plus rien à traiter |
| `PaymentStripeWebhookHandlerTest` | unit | chaque event type (existants + nouveaux), notamment chargebacks |
| `ChargebackServiceTest` | unit | `created` → flag `disputed` ; `closed/won` → levée ; `closed/lost` → reste gelé ; rejeu idempotent |
| `KycStripeWebhookHandlerTest` | unit | `identity.verification_session.*` (régression du comportement actuel) |
| `DeliveryEventListenerTest` | unit | bid gelé → pas de transfer + audit ; bid non gelé → transfer normal |
| `PaymentWebhookControllerIntegrationTest` | integration MockMvc | `/payments/webhook` : 200 immédiat, persistance RECEIVED, signature KO → 400 |
| `KycWebhookControllerIntegrationTest` | integration MockMvc | idem `/kyc/webhook` |
| `ChargebackControllerIntegrationTest` | integration MockMvc | `GET /admin/chargebacks` : ADMIN → 200 ; non-ADMIN → 403 |
| Migrations V84/V85 | integration | appliquées sans erreur sur base de test ; reprise `processed_stripe_events` |

Les tests webhooks existants (`PaymentWebhookBidPromotionTest`,
`StripeConnectWebhookAccountUpdatedTest`, `CashCommissionWebhookHandlerTest`,
`KycServiceTest`) sont adaptés au nouveau point d'entrée (handler au lieu de
`handleWebhook`/`processWebhook`).

---

## 8. Livrable — checklist prod

Document `docs/stripe-production-checklist.md` couvrant :

1. **Endpoints webhook à créer** dans le dashboard Stripe live :
   - `https://<api>/api/v1/payments/webhook` — **avec events Connect activés**.
   - `https://<api>/api/v1/kyc/webhook`.
2. **Liste exacte des 24 event types** à cocher par endpoint.
3. Récupération des deux **signing secrets** → variables d'env
   `STRIPE_WEBHOOK_PAYMENTS_SECRET` / `STRIPE_WEBHOOK_KYC_SECRET`.
4. Bascule des **clés API live** (`STRIPE_SECRET_KEY`).
5. Vérifs **Connect** : MCC `4215`, branding, capacités `card_payments` + `transfers`.
6. Vérifs **Identity** : pays autorisés.
7. `dony.kyc.enforce=true` et `dony.stripe.enforce=true` en prod.

---

## 9. Risques & points d'attention

- **Latence de traitement** : un event est traité jusqu'à `poll-interval` après
  réception (~10 s). Acceptable pour escrow/KYC/onboarding ; documenté.
- **Verrou de ligne pendant le handler** : un handler lent retient le verrou
  `FOR UPDATE` de sa ligne. Les handlers sont courts ; pas de risque de contention.
- **Listeners `AFTER_COMMIT`** : un échec dans un listener déclenché après le commit du
  processor n'est pas repris par l'inbox (l'event est déjà `PROCESSED`). Les handlers
  doivent réaliser les actions critiques de façon synchrone ; les listeners ne portent
  que des effets de bord non critiques (notifications). Conforme à la règle 18 du
  `CLAUDE.md` (`@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW`).
- **Capture de PaymentIntent** : toute capture passe par `markCapturedIfEscrow()`
  (règle 19) — inchangé, la garde de gel s'ajoute en amont.
- **`DROP TABLE processed_stripe_events`** : irréversible ; les `event_id` sont
  d'abord migrés en `PROCESSED` dans l'inbox pour ne pas rejouer l'historique.

---

## 10. Définition de terminé

- [ ] Migrations V84/V85 appliquées sans erreur, historique repris.
- [ ] Inbox + worker opérationnels ; les 2 webhooks répondent 200 immédiatement.
- [ ] 24 event types traités ; chargebacks créent un `ChargebackEntity` + gel du bid.
- [ ] `GET /admin/chargebacks` protégé `hasRole('ADMIN')`.
- [ ] `audit_log` alimenté pour chaque action significative.
- [ ] Erreurs RFC 7807, soft delete sur `chargebacks`.
- [ ] `./mvnw test` à 0 rouge, couverture JaCoCo ≥ 90 %.
- [ ] `application-prod.yml` complété + `docs/stripe-production-checklist.md` rédigé.
- [ ] Doc de clôture dans `docs/stories-done/`.
