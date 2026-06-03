-- Persistance du consentement analytics RGPD (source de vérité backend).
-- Colonnes nullable (pas de DEFAULT) : null = l'utilisateur n'a jamais répondu,
-- ce qui se distingue de FALSE (refusé explicitement).
ALTER TABLE users ADD COLUMN analytics_consent BOOLEAN;            -- null = jamais répondu
ALTER TABLE users ADD COLUMN analytics_consent_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN analytics_consent_version VARCHAR(32);
ALTER TABLE users ADD COLUMN analytics_consent_source VARCHAR(32);
