-- V50__users_add_deletion_requested_at.sql
ALTER TABLE users ADD COLUMN IF NOT EXISTS deletion_requested_at TIMESTAMPTZ;
