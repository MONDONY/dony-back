-- Stocke le fuseau horaire de départ pour convertir correctement departure_date/time en UTC.
-- Valeur par défaut 'Europe/Paris' couvre toutes les annonces existantes (Paris/Lyon/Marseille).
ALTER TABLE announcements
    ADD COLUMN timezone VARCHAR(50) NOT NULL DEFAULT 'Europe/Paris';
