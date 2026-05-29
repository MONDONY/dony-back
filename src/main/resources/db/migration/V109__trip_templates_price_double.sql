-- Aligne price_per_kg sur le mapping JPA Double (double precision).
-- V108 l'avait créé en NUMERIC, ce qui échoue à la validation Hibernate.
ALTER TABLE trip_templates
    ALTER COLUMN price_per_kg TYPE DOUBLE PRECISION;
