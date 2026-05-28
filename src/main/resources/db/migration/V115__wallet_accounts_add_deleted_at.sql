-- V114__wallet_accounts_add_deleted_at.sql
ALTER TABLE wallet_accounts ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;
