-- V12: Add missing deleted_at column to cancellations and rematch_suggestions
-- CancellationEntity extends BaseEntity which requires deleted_at for soft-delete support

ALTER TABLE cancellations
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE NULL;

ALTER TABLE rematch_suggestions
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE NULL;

CREATE INDEX IF NOT EXISTS idx_cancellations_deleted_at     ON cancellations (deleted_at);
CREATE INDEX IF NOT EXISTS idx_rematch_suggestions_deleted_at ON rematch_suggestions (deleted_at);
