-- V136 : drapeau de suspension de publication (D4). Plus léger qu'un UserStatus.SUSPENDED :
-- bloque la publication de trajets, jamais le login. Décidé par l'admin (jamais auto).
ALTER TABLE users ADD COLUMN publishing_suspended BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN publishing_suspended_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN publishing_suspended_reason VARCHAR(255);

CREATE INDEX idx_users_publishing_suspended ON users (publishing_suspended) WHERE publishing_suspended = TRUE;
