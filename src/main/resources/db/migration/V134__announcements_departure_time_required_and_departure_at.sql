-- V134 : heure de départ obligatoire + departure_at canonique (date + heure, fuseau ville de départ).
-- Sert de backstop temporel au verrou d'annulation après remise (now >= departure_at => annulation 409).
-- Jamais modifier une migration existante ; soft-delete inchangé ; audit_log non touché.

-- 1) Backfill des heures manquantes : midi heure locale (dégradation gracieuse, cf. D1).
UPDATE announcements
   SET departure_time = TIME '12:00'
 WHERE departure_time IS NULL;

-- 2) Nouvelle colonne canonique (nullable le temps du backfill).
ALTER TABLE announcements ADD COLUMN departure_at TIMESTAMP WITH TIME ZONE;

-- 3) Backfill : (date + heure) interprété dans le fuseau de l'annonce (défaut Europe/Paris).
UPDATE announcements
   SET departure_at = ((departure_date + departure_time) AT TIME ZONE COALESCE(timezone, 'Europe/Paris'))
 WHERE departure_at IS NULL;

-- 4) Verrouillage : les deux colonnes deviennent obligatoires (validation applicative déjà en place).
ALTER TABLE announcements ALTER COLUMN departure_time SET NOT NULL;
ALTER TABLE announcements ALTER COLUMN departure_at  SET NOT NULL;
