-- V44: Add PRO account and country fields to users table.
-- Note: user_roles table already exists from V1. This migration adds the new columns only.

ALTER TABLE users ADD COLUMN IF NOT EXISTS is_pro_account BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS pro_company_name VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS pro_siret VARCHAR(14);
ALTER TABLE users ADD COLUMN IF NOT EXISTS country VARCHAR(2) NOT NULL DEFAULT 'FR';
