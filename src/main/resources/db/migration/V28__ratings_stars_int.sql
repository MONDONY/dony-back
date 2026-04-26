-- Fix stars column type to INTEGER (Java int maps to INTEGER, not SMALLINT)
ALTER TABLE ratings ALTER COLUMN stars TYPE INTEGER;
