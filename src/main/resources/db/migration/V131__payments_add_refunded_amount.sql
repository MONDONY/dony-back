-- V131 : montant cumulé remboursé (valeur absolue renvoyée par Stripe charge.amount_refunded).
-- Permet de tracer les remboursements partiels sans écraser le statut du paiement.
ALTER TABLE payments ADD COLUMN refunded_amount NUMERIC(10,2);
