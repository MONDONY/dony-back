-- Phase 1 — Taux de commission par utilisateur + snapshot sur le bid.
-- Cf. docs/specs/commission-rate-overrides-and-promo.md

-- Override de taux de commission par utilisateur (voyageur ou expéditeur). NULL = taux global.
ALTER TABLE users ADD COLUMN IF NOT EXISTS commission_rate_override DECIMAL(4,3)
    CHECK (commission_rate_override IS NULL
           OR (commission_rate_override >= 0 AND commission_rate_override < 1));

-- Snapshot du taux effectif appliqué à un bid, figé à la création du paiement.
-- Source de vérité pour remboursements / payouts / analytics : ne jamais recalculer
-- depuis le taux courant (le global ou un override peuvent changer après coup).
ALTER TABLE bids ADD COLUMN IF NOT EXISTS commission_rate DECIMAL(4,3);

-- Backfill : les bids existants ont été facturés au taux global historique (12 %).
UPDATE bids SET commission_rate = 0.12 WHERE commission_rate IS NULL;
