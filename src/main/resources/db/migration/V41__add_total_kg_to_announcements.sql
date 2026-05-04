-- Capacité totale initiale du trajet (figée à la création).
-- available_kg = kg restants (décrémentés à chaque bid ACCEPTED, ré-incrémentés sur annulation post-acceptation).
-- total_kg permet d'afficher la barre de remplissage côté UI : booked = total_kg - available_kg.
ALTER TABLE announcements
    ADD COLUMN total_kg NUMERIC(5, 2);

-- Backfill : reconstitue la capacité initiale = restant + poids des bids dont le kilo n'a jamais été rendu.
-- Statuts dont le poids a été déduit et non restitué : ACCEPTED, COMPLETED, NO_SHOW, PARCEL_REFUSED.
UPDATE announcements a
SET total_kg = a.available_kg + COALESCE((
    SELECT SUM(b.weight_kg)
    FROM bids b
    WHERE b.announcement_id = a.id
      AND b.status IN ('ACCEPTED', 'COMPLETED', 'NO_SHOW', 'PARCEL_REFUSED')
), 0);

ALTER TABLE announcements
    ALTER COLUMN total_kg SET NOT NULL;

COMMENT ON COLUMN announcements.total_kg IS
    'Capacité totale initiale du trajet en kg (figée). Sert à calculer le remplissage : booked = total_kg - available_kg.';
