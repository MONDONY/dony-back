-- V61__negotiation_threads_split_accept_flow.sql
-- Split the negotiation accept flow into 3 atomic steps:
--   OPEN → AWAITING_TRIP (sender clicked Accept, traveler must pick/create a trajet)
--   AWAITING_TRIP → AWAITING_PAYMENT (traveler linked a trajet, sender must pay)
--   AWAITING_PAYMENT → ACCEPTED (Stripe payment confirmed, finalize + auto-reject competing threads)
ALTER TABLE negotiation_threads
    DROP CONSTRAINT chk_neg_thread_status;

ALTER TABLE negotiation_threads
    ADD CONSTRAINT chk_neg_thread_status CHECK (
        status IN ('OPEN', 'AWAITING_TRIP', 'AWAITING_PAYMENT', 'ACCEPTED', 'REJECTED', 'AUTO_REJECTED', 'EXPIRED')
    );
