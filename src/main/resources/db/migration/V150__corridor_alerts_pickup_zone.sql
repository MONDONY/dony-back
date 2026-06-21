-- Phase 2 — Alerte trajet « zone de remise » (option en plus du corridor).
-- L'expéditeur (direction SENDER_WANTS_TRIPS) peut, en plus du corridor
-- ville→ville, restreindre aux trajets dont le point de remise (pickup) tombe
-- dans un cercle (centre + rayon). Champs nullables : sans eux, l'alerte reste
-- un corridor pur (comportement inchangé).
ALTER TABLE corridor_alerts
    ADD COLUMN center_lat    NUMERIC(9, 6),
    ADD COLUMN center_lng    NUMERIC(9, 6),
    ADD COLUMN radius_km     INTEGER,
    ADD COLUMN center_label  VARCHAR(160);
