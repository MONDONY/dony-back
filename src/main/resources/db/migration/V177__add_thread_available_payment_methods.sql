-- V177 : SET des modes de paiement réellement fournissables par le voyageur,
-- calculé au trip-linking (intersection colis.accepted ∩ capacité voyageur).
-- Format identique à accepted_payment_methods : {STRIPE,CASH}. NULL tant que le
-- trajet n'est pas lié.
ALTER TABLE negotiation_threads
    ADD COLUMN available_payment_methods TEXT NULL;

-- Backfill des threads déjà liés (AWAITING_PAYMENT / ACCEPTED) : on préserve le
-- mode déjà choisi comme SET singleton pour ne pas casser les négociations en vol.
UPDATE negotiation_threads
SET available_payment_methods = '{' || payment_method || '}'
WHERE payment_method IS NOT NULL
  AND available_payment_methods IS NULL;
