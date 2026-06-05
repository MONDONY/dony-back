-- Capacité excédentaire publique sur trajet dédié.
-- reserved_kg     : part réservée à la négociation (immuable), 0 pour trajets normaux.
-- surplus_eligible: négociation payée → le voyageur peut ouvrir le surplus.
-- surplus_published: surplus ouvert et visible dans la recherche publique.
ALTER TABLE announcements
    ADD COLUMN reserved_kg NUMERIC(5,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN surplus_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN surplus_published BOOLEAN NOT NULL DEFAULT FALSE;

-- Index partiel : la recherche publique inclut les trajets dédiés au surplus ouvert.
CREATE INDEX idx_announcements_surplus_published
    ON announcements (surplus_published)
    WHERE surplus_published = TRUE;
