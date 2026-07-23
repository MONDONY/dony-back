-- V178 : horodatage de la dernière relance émise sur un fil de négociation,
-- pour le rate-limit du bouton « Relancer » (1 relance / heure). NULL = jamais relancé.
ALTER TABLE negotiation_threads
    ADD COLUMN last_nudge_at TIMESTAMP NULL;
