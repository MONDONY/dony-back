# Changelog

All notable changes to the Dony backend are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased] — 2026-05-05

### Added
- Stripe Connect `on_behalf_of` on all PaymentIntents (traveler as settlement merchant)
- Unified user model: `Set<Role>` with SENDER+TRAVELER for all users; `StripeAccountStatus` enum replacing `stripeOnboarded` boolean
- PRO account feature: `POST /auth/me/upgrade-to-pro`, `isProAccount` flag, `proCompanyName`, `proSiret`
- `country` field on users (default "FR"), used for Connect account creation
- Connect account creation with full `AccountCreateParams`: capabilities (cardPayments.requested=true, transfers), businessProfile (MCC 4215), daily payouts schedule
- `StripeOnboardingCompletedEvent` Spring event for downstream listeners
- Pre-capture traveler eligibility re-verification in `BidAcceptedEventListener`
- `TravelerNotEligibleForPaymentException` (HTTP 422) when Connect onboarding incomplete
- `GET /api/v1/config/commission-rate` public endpoint (value from application.yml)
- Deep links: custom scheme `dony://` for local dev (Universal Links deferred to production)
- Flutter: Stripe Connect onboarding screens, upgrade PRO screen, PRO badge on trip detail
- Flutter: dynamic commission rate from API (removes hardcoded 12%)

### Changed
- `PaymentIntentCreateParams`: added `on_behalf_of` and `statementDescriptorSuffix("DONY")`
- `handleAccountUpdated` webhook now requires BOTH `chargesEnabled` AND `payoutsEnabled` for `ONBOARDING_COMPLETE`
- `refreshConnectAccount` now uses same status-derivation logic as webhook (includes REJECTED/DISABLED)

### Migration notes (dev only — no prod data)
- Flyway V44: `user_roles` table, backfill SENDER+TRAVELER for all users, PRO fields, `country`
- Flyway V45: `stripe_account_status` column, backfill from `stripe_onboarded`, drop `stripe_onboarded`
