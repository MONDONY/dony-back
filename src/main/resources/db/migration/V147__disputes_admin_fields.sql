ALTER TABLE disputes
    ADD COLUMN IF NOT EXISTS resolved_at        TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS resolution_note    TEXT,
    ADD COLUMN IF NOT EXISTS beneficiary_user_id UUID;
