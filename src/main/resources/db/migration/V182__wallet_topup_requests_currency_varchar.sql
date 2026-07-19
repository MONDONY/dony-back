-- Convertir currency de CHAR(3) vers VARCHAR(3) pour compatibilite Hibernate validate
-- CHAR(3) en PostgreSQL est stocke comme bpchar et cree un mismatch avec le type JPA
-- attendu (String -> varchar), meme probleme deja rencontre sur cities.country_code
-- (cf. V54) et countries.country_code (cf. V129).
ALTER TABLE public.wallet_topup_requests ALTER COLUMN currency TYPE VARCHAR(3);
