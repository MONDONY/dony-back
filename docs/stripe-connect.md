# Stripe Connect — Architecture & Integration Notes

**Last updated:** 2026-05-05 (PR 5 — `on_behalf_of` migration)

This document covers the design decisions, lifecycle, and configuration of Stripe Connect on the Dony platform. For the end-to-end payment flow, see [`payments-flow.md`](./payments-flow.md).

---

## 1. Why `on_behalf_of`

### Fiscal / Legal

Without `on_behalf_of`, the **platform** is the merchant of record. The traveler's Stripe account receives transfers, but Stripe and card networks attribute the sale to Dony. This is legally incorrect: the traveler is the one providing the courier service and taking on the liability for the goods.

With `on_behalf_of=<traveler_connect_account_id>`, the **traveler's Connect account is the settlement merchant**. Stripe reports the traveler as the seller. This is the correct legal posture in France and in most West African jurisdictions where Dony operates (Dakar, Abidjan, Bamako, Douala).

### UX

The sender's bank statement shows the **traveler's business name** (or their name for Express individual accounts), not "Dony". This reduces chargebacks from confused senders who do not recognise a generic platform charge.

### Regulatory / Stripe capability requirement

`on_behalf_of` requires the connected account to have the **`card_payments` capability** active. This is why `AccountCreateParams` explicitly requests:

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

Stripe will not accept a `PaymentIntent` with `on_behalf_of` unless `card_payments` is enabled on the destination account. Attempting to do so returns a Stripe `invalid_request_error`.

---

## 2. Connect Account Lifecycle

### State diagram

```
                   first trip creation
NOT_CREATED ─────────────────────────────► PENDING_ONBOARDING
                                                  │
                           webhook account.updated │
                      (chargesEnabled=true         │
                       AND payoutsEnabled=true)    │
                                                   ▼
                                         ONBOARDING_COMPLETE
                                         (bids can be placed)

PENDING_ONBOARDING ──────────────────────────────► REJECTED
   (disabled_reason matches "rejected.*")

PENDING_ONBOARDING ──────────────────────────────► DISABLED
   (other disabled_reason present)
```

### State descriptions

| Status | Meaning | What is blocked |
|--------|---------|-----------------|
| `NOT_CREATED` | Traveler has no Stripe Express account yet | Cannot create trips |
| `PENDING_ONBOARDING` | Account created; Stripe onboarding link sent | Bids blocked; PaymentIntent capture blocked |
| `ONBOARDING_COMPLETE` | Both `charges_enabled` AND `payouts_enabled` are `true` | Nothing — full functionality |
| `REJECTED` | Stripe rejected the account (`disabled_reason` starts with `rejected.*`) | All payment operations blocked; user must contact support |
| `DISABLED` | Some other restriction applied | Capture blocked; `TravelerNotEligibleForPaymentException` (HTTP 422) on bid acceptance |

### Trigger: `NOT_CREATED` → `PENDING_ONBOARDING`

Called by `ConnectService.createOrRetrieveOnboardingLink()` when a traveler creates their first trip. The link is generated once and surfaced to the Flutter app as a deep link into the Stripe Express onboarding flow.

### Trigger: `PENDING_ONBOARDING` → `ONBOARDING_COMPLETE`

Driven by the `account.updated` Stripe webhook. The handler calls `deriveStripeAccountStatus(Account)`, which returns `ONBOARDING_COMPLETE` only when:

```java
account.getChargesEnabled() && account.getPayoutsEnabled()
```

On the **first** transition to `ONBOARDING_COMPLETE`, a `StripeOnboardingCompletedEvent` Spring event is published so downstream listeners (e.g., notification service) can act without coupling.

### Pre-capture re-verification

Even after a bid is accepted, `BidAcceptedEventListener` re-calls `refreshConnectAccount()` immediately before `pi.capture()`. This guards against edge cases where a traveler's account is disabled between bid acceptance and payment capture.

---

## 3. Individual vs Company (PRO Badge)

### Default — `INDIVIDUAL`

When a traveler creates their first trip and no Connect account exists, the platform creates an **Express account** with:

```java
.setBusinessType(AccountCreateParams.BusinessType.INDIVIDUAL)
```

This is the default for travellers acting as private individuals.

### PRO Upgrade — `COMPANY`

After `POST /api/v1/auth/me/upgrade-to-pro`, the user's `isProAccount` flag is set to `true` and `proCompanyName` / `proSiret` are stored. For **new** Connect account creation after this upgrade, the platform uses:

```java
.setBusinessType(AccountCreateParams.BusinessType.COMPANY)
```

### Known limitation: business_type is immutable after creation

Stripe does **not** allow changing `business_type` on an existing account. A traveler who:

1. Created at least one trip (Connect account created as `INDIVIDUAL`), and then
2. Upgraded to PRO

will have a mismatched Connect account. The `POST /auth/me/upgrade-to-pro` endpoint detects this and responds with **HTTP 409 Conflict** to prevent silent inconsistency. The user must contact support to have the old account closed and a new one provisioned.

### PRO badge semantics (MVP)

The PRO badge is **purely cosmetic** in MVP. It signals to senders that the traveler is a verified business entity, which may increase trust and booking rates. No additional capabilities or fee tiers are unlocked by PRO status in the current version.

---

## 4. Webhook Events Handled (`account.*`)

### Endpoint

```
POST /api/v1/payments/webhook
```

Public endpoint (no Firebase token required). Stripe signature is verified on every request:

```java
Webhook.constructEvent(payload, stripeSignatureHeader, stripeWebhookSecret);
```

### `account.updated`

This is the **only** account-level event handled. All Connect account state transitions go through this single handler.

**Handler chain:**

```
PaymentService.handleAccountUpdated(event)
    └─► deriveStripeAccountStatus(Account account)
            ├─ chargesEnabled && payoutsEnabled  →  ONBOARDING_COMPLETE
            ├─ disabled_reason starts with "rejected."  →  REJECTED
            ├─ disabled_reason present (other)  →  DISABLED
            └─ (default)  →  PENDING_ONBOARDING
```

**Idempotency:** `StripeOnboardingCompletedEvent` is published **only on the first transition** to `ONBOARDING_COMPLETE`. The handler compares the incoming derived status with the current persisted `StripeAccountStatus`. If already `ONBOARDING_COMPLETE`, the Spring event is not re-published and no audit log entry is written for a no-op update.

**Audit log:** every status change (even PENDING → PENDING no-op excluded) is written to `audit_log` with `entity_type=STRIPE_ACCOUNT` and the new status as metadata.

---

## 5. Configuration Reference

```yaml
# application.yml (relevant excerpt)
dony:
  stripe:
    connect:
      mcc: "4215"                          # Courier Services
                                           # TODO: validate vs 4214 (Delivery Services)
                                           #       with legal before prod go-live
      product-description: "Dony — P2P courier marketplace connecting African diaspora senders and travelers"
      business-url: "https://dony.app"
      return-url: "dony://stripe/onboarding/complete"
      # ^ dev: custom URI scheme (Android intent-filter / iOS URL scheme)
      # ^ prod: must be migrated to HTTPS Universal Link (see docs/deep-links.md)
      refresh-url: "dony://stripe/onboarding/refresh"
      # ^ same note — prod requires HTTPS Universal Link

  commission:
    rate: 0.12    # 12 % — exposed read-only via GET /api/v1/config/commission-rate
```

### Environment variables (never in code)

| Variable | Purpose |
|----------|---------|
| `STRIPE_SECRET_KEY` | Platform secret key (sk_live_… / sk_test_…) |
| `STRIPE_WEBHOOK_SECRET` | Signing secret for `POST /payments/webhook` |

### MCC note

MCC `4215` (Courier Services, Air or Ground, Frieght) is used for MVP. MCC `4214` (Motor Freight Carriers and Trucking) may be more accurate depending on legal review. This must be confirmed with the Stripe account team and legal counsel **before production go-live**.

### Deep-link note

The `return-url` and `refresh-url` use the custom `dony://` URI scheme. Apple App Store and Google Play Store policies, as well as Stripe's own recommendations, require **HTTPS Universal Links** (iOS) and **Android App Links** (Android) in production. The `dony://` scheme is acceptable for local development and TestFlight/internal testing only. See `docs/deep-links.md` for the migration plan.
