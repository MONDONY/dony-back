-- V138 : compteur de réputation expéditeur pour les incidents de remise (D5/D6).
-- No-show confirmé ou annulation après remise par l'expéditeur. Distinct des compteurs
-- voyageur (no_show_count / cancellation_count). N'incrémente qu'au statut CONFIRMED (D8).
ALTER TABLE users
    ADD COLUMN sender_handover_incident_count INTEGER NOT NULL DEFAULT 0;
