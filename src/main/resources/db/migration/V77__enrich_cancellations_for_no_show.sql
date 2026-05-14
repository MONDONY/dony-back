-- La colonne reason existe déjà en TEXT avec des données libres.
-- On ajoute no_show_status (CONFIRMED par défaut) et contestation_deadline.

ALTER TABLE cancellations ADD COLUMN IF NOT EXISTS no_show_status VARCHAR(25)
    NOT NULL DEFAULT 'CONFIRMED'
    CHECK (no_show_status IN ('CONFIRMED', 'PENDING_CONFIRMATION', 'CONTESTED'));

ALTER TABLE cancellations ADD COLUMN IF NOT EXISTS contestation_deadline TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_cancellations_pending ON cancellations(contestation_deadline)
    WHERE no_show_status = 'PENDING_CONFIRMATION';
