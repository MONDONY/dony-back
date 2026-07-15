-- V174: Un bid peut avoir un litige de départ (SENDER_NO_SHOW_CONTESTED) ET,
-- plus tard dans sa vie, un litige d'arrivée (RECIPIENT_NO_SHOW_CONTESTED /
-- TRAVELER_DELIVERY_NO_SHOW_CONTESTED / leurs variantes non-contestées).
-- L'unicité passe de (bid_id) à (bid_id, type).

ALTER TABLE disputes DROP CONSTRAINT uq_disputes_bid_id;
ALTER TABLE disputes ADD CONSTRAINT uq_disputes_bid_id_type UNIQUE (bid_id, type);
