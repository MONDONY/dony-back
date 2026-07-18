-- V176__currencies.sql
-- Référentiel devises (spec 2026-07-18-modele-devise-design.md §4.2).
-- peg_rate_to_eur NULL = devise flottante (affichage local supprimé).
-- rounding_increment en unités mineures (XOF/XAF : multiples de 5 F, usage local).
CREATE TABLE currencies (
  code                CHAR(3)       PRIMARY KEY,
  numeric_code        SMALLINT      NOT NULL,
  minor_unit          SMALLINT      NOT NULL,
  symbol              VARCHAR(8)    NOT NULL,
  peg_rate_to_eur     NUMERIC(18,8),
  rounding_increment  INTEGER       NOT NULL DEFAULT 1,
  enabled             BOOLEAN       NOT NULL DEFAULT true
);

INSERT INTO currencies (code, numeric_code, minor_unit, symbol, peg_rate_to_eur, rounding_increment, enabled) VALUES
  ('EUR', 978, 2, '€',     NULL,       1, true),
  ('XOF', 952, 0, 'F CFA', 655.957,    5, true),
  ('XAF', 950, 0, 'F CFA', 655.957,    5, true);
