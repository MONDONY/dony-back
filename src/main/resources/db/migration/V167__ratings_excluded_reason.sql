-- Motif d'exclusion d'un avis de la moyenne — jusqu'ici uniquement dans
-- le payload d'audit_log, invisible pour le back-office.
ALTER TABLE ratings ADD COLUMN IF NOT EXISTS excluded_reason TEXT;
