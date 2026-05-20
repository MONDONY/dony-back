-- Remplace la contrainte UNIQUE classique sur phone_number par un partial index
-- qui exclut les comptes soft-deleted (deleted_at IS NOT NULL).
-- Cela permet de recréer un compte après suppression avec le même numéro.
ALTER TABLE users DROP CONSTRAINT uq_users_phone_number;
CREATE UNIQUE INDEX uq_users_phone_number ON users (phone_number) WHERE deleted_at IS NULL;
