-- V66__bids_link_negotiation_thread.sql
-- Make a bid created from the new package_request marketplace flow possible.
-- When a NegotiationThread reaches ACCEPTED (sender paid), we create a Bid
-- with status=ACCEPTED so the shipment shows up in the existing
-- "Mes envois → En cours" tab. The sender will fill recipient/declared-value
-- later via complete-details, so those columns must be nullable.

-- 1) Relax NOT NULL on declared_value_eur (filled later via complete-details).
ALTER TABLE bids ALTER COLUMN declared_value_eur DROP NOT NULL;

-- 2) Relax the CHECK constraint to allow NULL (NULL values bypass the check
--    automatically, but the existing constraint already does — keeping it).
ALTER TABLE bids DROP CONSTRAINT IF EXISTS chk_bids_declared_value;
ALTER TABLE bids ADD CONSTRAINT chk_bids_declared_value
    CHECK (declared_value_eur IS NULL OR (declared_value_eur > 0 AND declared_value_eur <= 500));

-- 3) Optional FK back to the negotiation thread that created this bid,
--    so the marketplace flow can sync recipient/declared-value updates back
--    onto the bid via the package_request complete-details flow.
ALTER TABLE bids
    ADD COLUMN linked_negotiation_thread_id UUID;

ALTER TABLE bids
    ADD CONSTRAINT fk_bids_linked_thread
    FOREIGN KEY (linked_negotiation_thread_id)
    REFERENCES negotiation_threads (id)
    ON DELETE SET NULL;

CREATE UNIQUE INDEX idx_bids_linked_thread
    ON bids (linked_negotiation_thread_id)
    WHERE linked_negotiation_thread_id IS NOT NULL;
