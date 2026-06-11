-- V133 : fenêtre de remise au niveau du trajet (héritée par les bids à l'acceptation).
-- Nullable : les annonces existantes n'en ont pas (dégradation gracieuse). Obligatoire
-- via la validation applicative pour toute nouvelle création/édition.
ALTER TABLE announcements ADD COLUMN handover_window_start TIMESTAMP WITH TIME ZONE;
ALTER TABLE announcements ADD COLUMN handover_window_end   TIMESTAMP WITH TIME ZONE;
