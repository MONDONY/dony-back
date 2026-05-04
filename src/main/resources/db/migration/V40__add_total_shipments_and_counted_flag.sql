-- Sender-side counter: incremented each time one of the user's bids reaches COMPLETED.
ALTER TABLE users
  ADD COLUMN total_shipments INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN users.total_shipments IS
  'Number of parcels successfully delivered for this sender. Incremented on each COMPLETED bid where this user is sender.';

-- Idempotence flag on bid (prevents double-count on event replay).
ALTER TABLE bids
  ADD COLUMN shipment_counted BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN bids.shipment_counted IS
  'Idempotence flag: TRUE once users.total_shipments has been incremented for this bid''s sender. Prevents double-counting on event replay.';
