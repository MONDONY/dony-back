-- V177__payments_settlement.sql
-- Règlement constaté du flux Stripe (spec devise §4.1). settlement IS NULL =
-- règlement pas encore constaté (paiement créé, non capturé).
-- ⚠️ Le backfill réécrit TOUTE la table payments (lignes soft-deleted incluses,
-- voulu : historique comptable complet). Fenêtre de faible trafic recommandée.
ALTER TABLE payments
  ADD COLUMN settlement_currency        CHAR(3),
  ADD COLUMN settlement_amount_minor    BIGINT,
  ADD COLUMN settlement_fx_rate         NUMERIC(18,8),
  ADD COLUMN settlement_rate_source     VARCHAR(16);

ALTER TABLE payments ADD CONSTRAINT chk_settlement_all_or_none
  CHECK ((settlement_currency IS NULL) = (settlement_amount_minor IS NULL)
     AND (settlement_currency IS NULL) = (settlement_rate_source IS NULL));

-- Historique : tout paiement existant a été réglé en EUR (montant initial,
-- jamais netté des remboursements — refunded_amount reste la source refunds).
UPDATE payments
   SET settlement_currency     = 'EUR',
       settlement_amount_minor = ROUND(amount * 100),
       settlement_fx_rate      = 1,
       settlement_rate_source  = 'NONE'
 WHERE settlement_currency IS NULL;
