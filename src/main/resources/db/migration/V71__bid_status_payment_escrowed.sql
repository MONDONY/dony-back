-- PAYMENT_ESCROWED: statut intermédiaire entre AWAITING_PAYMENT et ACCEPTED.
-- Positionné par le webhook Stripe (payment_intent.amount_capturable_updated)
-- lorsque le paiement est gelé en escrow mais avant que le voyageur confirme
-- via PUT /bids/:id/accept. Le voyageur peut encore refuser (REJECTED + remboursement).
-- bid.status est VARCHAR(20) via EnumType.STRING — aucune modification DDL requise.
COMMENT ON COLUMN bids.status IS
  'BidStatus: PENDING | AWAITING_PAYMENT | PAYMENT_ESCROWED | ACCEPTED | REJECTED | CANCELLED | COMPLETED | NO_SHOW | PARCEL_REFUSED | EXPIRED';
