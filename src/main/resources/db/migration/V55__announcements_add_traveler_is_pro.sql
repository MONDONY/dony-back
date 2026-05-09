-- Colonne dénormalisée pour trier les annonces PRO en premier sans JOIN coûteux.
-- Mise à jour via événement Spring quand le statut PRO du voyageur change.
ALTER TABLE announcements ADD COLUMN IF NOT EXISTS traveler_is_pro BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_announcements_traveler_is_pro ON announcements (traveler_is_pro);
