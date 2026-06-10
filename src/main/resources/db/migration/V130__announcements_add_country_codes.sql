-- Codes pays ISO-2 de départ/arrivée sur les annonces.
-- Nullable : les lignes existantes n'ont pas de code (résolu côté front par
-- géocodage de la ville). Le front les renseigne désormais à la création/édition,
-- et l'API renvoie le drapeau emoji correspondant via FlagService.
-- NB : VARCHAR(2) (et non CHAR(2)) pour compatibilité avec Hibernate
-- ddl-auto=validate, comme `countries` (cf. V129) et `cities` (cf. V54).
ALTER TABLE announcements ADD COLUMN departure_country_code VARCHAR(2);
ALTER TABLE announcements ADD COLUMN arrival_country_code   VARCHAR(2);
