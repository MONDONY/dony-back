-- V44: Add PRO account and country fields to users table.
-- Note: user_roles table already exists from V1. This migration adds the new columns only.

ALTER TABLE users ADD COLUMN IF NOT EXISTS is_pro_account BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS pro_company_name VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS pro_siret VARCHAR(14);
ALTER TABLE users ADD COLUMN IF NOT EXISTS country VARCHAR(2) NOT NULL DEFAULT 'FR';

-- Backfill: all existing users get SENDER + TRAVELER roles
INSERT INTO user_roles (user_id, role)
SELECT id, 'SENDER' FROM users
ON CONFLICT (user_id, role) DO NOTHING;

INSERT INTO user_roles (user_id, role)
SELECT id, 'TRAVELER' FROM users
ON CONFLICT (user_id, role) DO NOTHING;

-- Backfill ADMIN for users who had role='ADMIN' (if the role column still exists)
-- This uses DO $$ ... $$ block to safely check column existence first
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name='users' AND column_name='role'
    ) THEN
        INSERT INTO user_roles (user_id, role)
        SELECT id, 'ADMIN' FROM users WHERE role = 'ADMIN'
        ON CONFLICT (user_id, role) DO NOTHING;
    END IF;
END $$;
