-- Pivot vie privée : le téléphone et l'email quittent la base dony.
-- Firebase Auth en devient la seule source de vérité ; le backend les relit à la
-- demande depuis l'UID Firebase (voir FirebaseContactService). Un vol de la base
-- ne peut donc plus révéler les coordonnées des utilisateurs.
--
-- Aucun backfill n'est nécessaire : le produit n'est pas encore lancé, et toutes
-- les valeurs présentes ici existent déjà dans Firebase, qui les a créées.

-- Index portés par les colonnes supprimées (partial unique posé en V87, index
-- simple posé en V1). DROP COLUMN les retirerait en cascade, mais on l'écrit
-- explicitement pour que la migration se lise sans remonter l'historique.
DROP INDEX IF EXISTS uq_users_phone_number;
DROP INDEX IF EXISTS idx_users_phone_number;

ALTER TABLE users DROP COLUMN IF EXISTS phone_number;
ALTER TABLE users DROP COLUMN IF EXISTS email;
