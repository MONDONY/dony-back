-- V70__users_add_rating_count.sql
-- Dénormalise le nombre d'avis reçus sur users.rating_count pour éviter le N+1
-- lors de la construction des SenderPublicProfile dans PackageRequestService.
-- La valeur est maintenue par RatingService.recalculateAverageRating() au même
-- titre que average_rating.

ALTER TABLE users
    ADD COLUMN rating_count INT NOT NULL DEFAULT 0;

-- Backfill : nombre d'avis inclus dans la moyenne (excluded_from_average = FALSE)
-- et non supprimés (soft delete).
UPDATE users
SET rating_count = (
    SELECT COUNT(*)
    FROM ratings r
    WHERE r.rated_user_id = users.id
      AND r.excluded_from_average = FALSE
      AND r.deleted_at IS NULL
);
