CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE cities (
    id           BIGINT       PRIMARY KEY,
    name         VARCHAR(200) NOT NULL,
    country_code CHAR(2)      NOT NULL,
    country_name VARCHAR(100) NOT NULL,
    population   BIGINT       NOT NULL DEFAULT 0,
    latitude     NUMERIC(9,6) NOT NULL,
    longitude    NUMERIC(9,6) NOT NULL
);

CREATE INDEX idx_cities_name_trigram ON cities USING GIN (name gin_trgm_ops);
CREATE INDEX idx_cities_population   ON cities (population DESC);
CREATE INDEX idx_cities_country_code ON cities (country_code);
