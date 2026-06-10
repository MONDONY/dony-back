-- Table des pays ISO-2 avec leur emoji drapeau.
-- Démarre VIDE : remplie paresseusement par FlagService au premier accès à un pays
-- (le drapeau est calculé depuis le code ISO-2, persisté, puis relu ensuite).
-- Ne PAS seeder depuis `cities` : cette table est peuplée au runtime par
-- GeoNamesDataLoader, donc vide au moment où les migrations Flyway s'exécutent.
-- NB : country_code en VARCHAR(2) (et non CHAR(2)) pour compatibilité avec
-- Hibernate ddl-auto=validate, comme la table `cities` (cf. V54). CHAR(2) =
-- bpchar côté Postgres, que Hibernate refuse face à un mapping String/varchar.
CREATE TABLE countries (
    country_code VARCHAR(2)   PRIMARY KEY,
    country_name VARCHAR(100),
    flag         VARCHAR(16),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
