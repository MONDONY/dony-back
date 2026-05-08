-- Remplacer la contrainte UNIQUE case-sensitive par un index unique normalisé
ALTER TABLE corridors DROP CONSTRAINT uq_corridor;

CREATE UNIQUE INDEX uq_corridor_normalized
    ON corridors (lower(trim(departure_city)), lower(trim(arrival_city)));

-- Normaliser les données initiales (seed de V52)
UPDATE corridors SET
    departure_city = trim(departure_city),
    arrival_city   = trim(arrival_city);

-- Ajouter l'index prefix B-tree pour autocomplete ILIKE 'Paris%' (plus rapide que GIN pour les préfixes)
CREATE INDEX idx_cities_name_prefix ON cities (lower(name) text_pattern_ops);
