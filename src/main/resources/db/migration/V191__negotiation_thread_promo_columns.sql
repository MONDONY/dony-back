-- Code promo appliqué par l'expéditeur à l'initiation du paiement (initiate-payment).
-- Persisté ici (et non uniquement sur PaymentEntity) car finalizeAfterPayment,
-- déclenché aussi bien par le webhook Stripe que par le /checkout synchrone,
-- n'a accès qu'au thread — c'est le seul point de passage garanti pour
-- transporter le code jusqu'au rachat (redeem) déclenché à la matérialisation du bid.
ALTER TABLE negotiation_threads
    ADD COLUMN promo_code VARCHAR(50),
    ADD COLUMN commission_rate NUMERIC(4,3);
