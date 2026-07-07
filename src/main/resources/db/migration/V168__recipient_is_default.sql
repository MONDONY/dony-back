ALTER TABLE recipients ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;

-- Un seul destinataire par défaut actif par utilisateur.
CREATE UNIQUE INDEX idx_recipients_user_default
    ON recipients (user_id)
    WHERE is_default = TRUE AND deleted_at IS NULL;
