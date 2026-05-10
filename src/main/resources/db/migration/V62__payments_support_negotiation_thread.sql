-- V62__payments_support_negotiation_thread.sql
-- Extend the `payments` table to support negotiation_thread payments alongside bid payments.
-- A payment is now bound to EITHER a bid_id OR a negotiation_thread_id (mutually exclusive).
ALTER TABLE payments
    ALTER COLUMN bid_id DROP NOT NULL;

ALTER TABLE payments
    ADD COLUMN negotiation_thread_id UUID REFERENCES negotiation_threads(id);

ALTER TABLE payments
    ADD CONSTRAINT chk_payments_exactly_one_scope CHECK (
        (bid_id IS NOT NULL AND negotiation_thread_id IS NULL)
     OR (bid_id IS NULL AND negotiation_thread_id IS NOT NULL)
    );

CREATE UNIQUE INDEX idx_payments_negotiation_thread_unique
    ON payments(negotiation_thread_id)
    WHERE negotiation_thread_id IS NOT NULL AND deleted_at IS NULL;
