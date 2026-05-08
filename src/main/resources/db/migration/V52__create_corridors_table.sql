CREATE TABLE corridors (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    departure_city   VARCHAR(200) NOT NULL,
    departure_country VARCHAR(100) NOT NULL,
    arrival_city     VARCHAR(200) NOT NULL,
    arrival_country  VARCHAR(100) NOT NULL,
    usage_count      INTEGER      NOT NULL DEFAULT 1,
    last_used_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_corridor UNIQUE (departure_city, arrival_city)
);

CREATE INDEX idx_corridors_usage ON corridors (usage_count DESC);

INSERT INTO corridors (departure_city, departure_country, arrival_city, arrival_country, usage_count) VALUES
    ('Paris',     'France', 'Dakar',           'Sénégal',        10),
    ('Paris',     'France', 'Abidjan',          'Côte d''Ivoire',  8),
    ('Paris',     'France', 'Bamako',           'Mali',             6),
    ('Paris',     'France', 'Douala',           'Cameroun',         5),
    ('Lyon',      'France', 'Abidjan',          'Côte d''Ivoire',   4),
    ('Marseille', 'France', 'Bamako',           'Mali',             3);
