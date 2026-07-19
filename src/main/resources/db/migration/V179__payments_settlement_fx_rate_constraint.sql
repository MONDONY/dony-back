-- V179__payments_settlement_fx_rate_constraint.sql
-- Corrige chk_settlement_all_or_none (V177) : settlement_fx_rate manquait de la
-- contrainte, permettant un etat partiel (3 colonnes renseignees, fx_rate NULL).
-- Aucune donnee existante affectee : le backfill V177 renseigne les 4 colonnes
-- sur toutes les lignes, et aucun code n'ecrit encore ces colonnes (Task 11).
ALTER TABLE payments DROP CONSTRAINT chk_settlement_all_or_none;

ALTER TABLE payments ADD CONSTRAINT chk_settlement_all_or_none
  CHECK ((settlement_currency IS NULL) = (settlement_amount_minor IS NULL)
     AND (settlement_currency IS NULL) = (settlement_rate_source IS NULL)
     AND (settlement_currency IS NULL) = (settlement_fx_rate IS NULL));
