ALTER TABLE payments
  ADD COLUMN legacy_destination_charge BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN stripe_charge_id          VARCHAR(255);

-- Tous les payments existants au moment du déploiement utilisent l'ancien flow
UPDATE payments SET legacy_destination_charge = true;

CREATE INDEX idx_payments_stripe_charge_id ON payments (stripe_charge_id);

COMMENT ON COLUMN payments.legacy_destination_charge IS 'true si le PaymentIntent a transfer_data.destination (capture transfère immédiatement au voyageur). false pour separate-charges-and-transfers.';
COMMENT ON COLUMN payments.stripe_charge_id IS 'Charge id, populé au webhook payment_intent.amount_capturable_updated. Nécessaire pour Transfer.create avec source_transaction.';
