-- V178__mobile_money_currency_freeze.sql
-- Gel du montant local (règle R2) + règlement constaté MM (spec §4.3).
-- mobile_money_payments est la table canonique du règlement mobile money
-- (le flux MM ne crée jamais de ligne payments).
ALTER TABLE mobile_money_payments
  ADD COLUMN amount_minor         BIGINT,
  ADD COLUMN fx_rate              NUMERIC(18,8),
  ADD COLUMN rate_source          VARCHAR(16),
  ADD COLUMN settled_amount_minor BIGINT;
