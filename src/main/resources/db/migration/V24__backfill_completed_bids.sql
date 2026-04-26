-- V24: Backfill bids livrés (ARRIVEE confirmée) avec status COMPLETED.
-- Corrige les bids créés avant que confirmDelivery() ne mette à jour le statut.
UPDATE bids
SET status     = 'COMPLETED',
    updated_at = NOW()
WHERE status     = 'ACCEPTED'
  AND deleted_at IS NULL
  AND EXISTS (
      SELECT 1
      FROM tracking_events
      WHERE tracking_events.bid_id   = bids.id
        AND tracking_events.event_type = 'ARRIVEE'
  );
