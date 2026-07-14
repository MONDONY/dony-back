-- Les favoris passent au hard delete (retrait utilisateur + cleanup scheduler).
-- Purge one-shot des lignes soft-deleted accumulées sous l'ancien régime.
DELETE FROM favorites WHERE deleted_at IS NOT NULL;
