ALTER TABLE bids
  ADD COLUMN payment_intent_id           VARCHAR(255),
  ADD COLUMN awaiting_payment_expires_at TIMESTAMP;

CREATE INDEX idx_bids_awaiting_payment
  ON bids (status, awaiting_payment_expires_at)
  WHERE status = 'AWAITING_PAYMENT';

CREATE INDEX idx_bids_payment_intent
  ON bids (payment_intent_id);

COMMENT ON COLUMN bids.payment_intent_id IS 'Stripe PaymentIntent linked to this bid (set at checkout creation)';
COMMENT ON COLUMN bids.awaiting_payment_expires_at IS 'Expiration de la fenêtre de paiement (NULL une fois payé)';
