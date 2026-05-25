-- V105__merge_favorites_into_subscriptions.sql
-- Migre les favoris existants en abonnements (push off), puis supprime la table favoris.
INSERT INTO traveler_subscriptions (id, sender_id, traveler_id, push_enabled, has_new, created_at, updated_at)
SELECT gen_random_uuid(), f.user_id, f.traveler_id, false, false, NOW(), NOW()
FROM favorite_travelers f
WHERE f.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM traveler_subscriptions ts
      WHERE ts.sender_id = f.user_id AND ts.traveler_id = f.traveler_id
  );

DROP TABLE favorite_travelers;
