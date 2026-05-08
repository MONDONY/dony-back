-- Convertir country_code de CHAR(2) vers VARCHAR(2) pour compatibilite Hibernate validate
-- CHAR(2) en PostgreSQL est stocke comme bpchar et cree un mismatch avec le type JPA attendu
ALTER TABLE cities ALTER COLUMN country_code TYPE VARCHAR(2);
