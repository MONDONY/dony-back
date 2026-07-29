# Tracking integration for package_requests — DEFERRED

## Why deferred

- Requires schema change to `tracking_events` (high-traffic table) via V60.
- Coupled with the Stripe integration TODO from `NegotiationService.accept()`.
- Production code path refactor (TrackingService, RecipientController, QR token generation).

## Scope when re-enabled

1. Migration V60: add `package_request_id` + `negotiation_thread_id` columns to `tracking_events`,
   relax `bid_id` NOT NULL, add CHECK constraint enforcing exactly-one-scope.
2. Refactor `TrackingService` to accept either a `bid_id` or `(package_request_id, negotiation_thread_id)`.
3. Generate QR token for accepted negotiation threads (analogous to current bid QR generation).
4. Listen to `PackageRequestAcceptedEvent` in `tracking/` package — generate QR token when payment is confirmed.
5. Add a `requests/` listener for `DeliveryConfirmedEvent` (scope=NEGOTIATION) that sets `package_requests.status = COMPLETED`.

## When to do this

After:
- Stripe integration in `NegotiationService.accept()` is real (V60 likely also adds `payments.negotiation_thread_id`).
- Marketplace feature is in production with at least 100 successful negotiations.

## Tracking

Story: post-MVP iteration. Linked back-issue TBD.
