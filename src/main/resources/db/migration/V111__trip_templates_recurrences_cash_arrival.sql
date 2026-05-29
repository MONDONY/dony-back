-- Cash + heure d'arrivée, repris du modèle vers la récurrence puis vers les trajets générés.
ALTER TABLE trip_templates
    ADD COLUMN cash_accepted BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN arrival_time  TIME;

ALTER TABLE trip_recurrences
    ADD COLUMN cash_accepted BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN arrival_time  TIME;
