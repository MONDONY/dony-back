ALTER TABLE announcements
    ADD COLUMN pricing_mode VARCHAR(10) NOT NULL DEFAULT 'KG'
        CONSTRAINT chk_announcements_pricing_mode CHECK (pricing_mode IN ('KG', 'MIXED'));
