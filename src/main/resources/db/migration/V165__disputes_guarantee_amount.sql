-- Montant du versement fonds de garantie (centimes).
-- Jusqu'ici le montant n'existait que dans le payload JSON de audit_log —
-- impossible à requêter pour l'export comptable des payouts.
ALTER TABLE disputes ADD COLUMN guarantee_amount_cents BIGINT;
