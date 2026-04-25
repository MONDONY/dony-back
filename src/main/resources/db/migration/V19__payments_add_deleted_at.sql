-- V19: Add soft-delete support to payments (align with BaseEntity convention)
ALTER TABLE payments ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;
