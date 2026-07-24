-- Chiffrement au repos (P0, phase 1) : les colonnes PII ci-dessous sont
-- désormais chiffrées côté application (EncryptedStringConverter, AES-256-GCM).
-- Le ciphertext Base64 (IV + tag + données) est plus long que le clair, donc
-- on élargit les colonnes. Pré-lancement : aucune donnée existante à migrer.
--
-- Champs choisis car jamais utilisés dans un WHERE / ORDER BY / UNIQUE
-- (le chiffrement randomisé casserait recherche/tri/unicité sinon).
-- Les identifiants indexés (users.phone_number, users.email) ne sont PAS ici :
-- ils nécessitent une colonne de hash déterministe dédiée (phase suivante).

ALTER TABLE users      ALTER COLUMN pro_siret     TYPE VARCHAR(255);

ALTER TABLE recipients ALTER COLUMN full_name     TYPE VARCHAR(255);
ALTER TABLE recipients ALTER COLUMN phone_e164    TYPE VARCHAR(255);
ALTER TABLE recipients ALTER COLUMN whatsapp_e164 TYPE VARCHAR(255);
