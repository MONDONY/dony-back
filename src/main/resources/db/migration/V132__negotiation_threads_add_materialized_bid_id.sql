-- V132 : référence du bid matérialisé après acceptation d'une négociation.
-- Permet au mobile d'ouvrir le détail du bid (suivi, no-show…) depuis le thread.
ALTER TABLE negotiation_threads ADD COLUMN materialized_bid_id UUID;
