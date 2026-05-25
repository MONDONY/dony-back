-- V105__merge_favorites_into_subscriptions.sql
-- Migre les favoris existants en abonnements (push off), puis supprime la table favoris.

-- 1) Insère un abonnement pour chaque favori actif qui n'a pas déjà un abonnement ACTIF.
--    Le filtre ts.deleted_at IS NULL évite de bloquer l'insert à cause d'un abonnement
--    soft-deleted (sinon le favori serait silencieusement perdu).
INSERT INTO traveler_subscriptions (id, sender_id, traveler_id, push_enabled, has_new, created_at, updated_at)
SELECT gen_random_uuid(), f.user_id, f.traveler_id, false, false, NOW(), NOW()
FROM favorite_travelers f
WHERE f.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM traveler_subscriptions ts
      WHERE ts.sender_id = f.user_id
        AND ts.traveler_id = f.traveler_id
        AND ts.deleted_at IS NULL
  );

-- 2) Réactive les abonnements soft-deleted dont le favori est encore actif
--    (la contrainte UNIQUE(sender_id, traveler_id) empêche un nouvel insert).
UPDATE traveler_subscriptions ts
SET deleted_at = NULL, has_new = false, updated_at = NOW()
FROM favorite_travelers f
WHERE f.deleted_at IS NULL
  AND ts.sender_id = f.user_id
  AND ts.traveler_id = f.traveler_id
  AND ts.deleted_at IS NOT NULL;

DROP TABLE favorite_travelers;
