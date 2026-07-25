-- Préférence de confidentialité : l'utilisateur peut refuser que son numéro soit
-- révélé à sa contrepartie, même une fois l'offre acceptée. Il reste joignable par
-- la messagerie dony, qui devient alors le seul canal de contact.
--
-- DEFAULT FALSE : le comportement historique (numéro échangé au tap une fois le
-- deal actif) reste celui de tous les comptes existants et des nouveaux comptes.
-- L'opt-in est explicite, depuis Réglages › Confidentialité.
ALTER TABLE users ADD COLUMN IF NOT EXISTS hide_phone_number BOOLEAN NOT NULL DEFAULT FALSE;
