-- V63__negotiation_threads_unique_active_only.sql
-- Allow a traveler to start a new negotiation thread on a package request
-- after a previous thread was REJECTED, AUTO_REJECTED, or EXPIRED.
-- The unique constraint must only block while a thread is "live" (open, awaiting,
-- accepted) — terminal statuses must not prevent retrying.

DROP INDEX IF EXISTS idx_neg_threads_unique_active;

CREATE UNIQUE INDEX idx_neg_threads_unique_active
  ON negotiation_threads (package_request_id, traveler_id)
  WHERE deleted_at IS NULL
    AND status IN ('OPEN', 'AWAITING_TRIP', 'AWAITING_PAYMENT', 'ACCEPTED');
