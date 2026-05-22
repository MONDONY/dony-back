-- En mode MIXED (grille articles), le prix par kg peut être 0 (tarification grille seule).
-- On remplace la contrainte > 0 par une contrainte mode-aware :
--   - mode KG (ou NULL) : price_per_kg > 0 obligatoire
--   - mode MIXED        : price_per_kg >= 0 autorisé
ALTER TABLE announcements
    DROP CONSTRAINT IF EXISTS chk_announcements_price_per_kg;

ALTER TABLE announcements
    ADD CONSTRAINT chk_announcements_price_per_kg
        CHECK (
            price_per_kg >= 0
            AND (
                pricing_mode = 'MIXED'
                OR price_per_kg > 0
            )
        );
