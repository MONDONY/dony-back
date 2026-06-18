-- Net négocié figé pour les bids issus d'une demande (flux négociation).
-- Le net/tarif affiché était recalculé live depuis announcement.price_per_kg, qui
-- peut diverger du prix négocié (annonce existante au tarif catalogue, ou ouverture
-- du surplus qui réécrit price_per_kg). On fige le prix d'accord sur le bid.
-- Null pour un bid d'offre directe.
ALTER TABLE bids ADD COLUMN negotiated_net_eur DECIMAL(10, 2);
