-- Contrainte de cohérence sur le snapshot de taux du bid (bids.commission_rate),
-- alignée sur users.commission_rate_override (V117) : NULL autorisé, sinon [0, 1[.
-- Le taux provient du CommissionRateResolver (borné), cette contrainte est une
-- défense en profondeur contre toute écriture aberrante.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'bids_commission_rate_range'
    ) THEN
        ALTER TABLE bids ADD CONSTRAINT bids_commission_rate_range
            CHECK (commission_rate IS NULL
                   OR (commission_rate >= 0 AND commission_rate < 1));
    END IF;
END $$;
