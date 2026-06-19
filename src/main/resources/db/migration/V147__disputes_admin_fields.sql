ALTER TABLE disputes
    ADD COLUMN resolution         VARCHAR(50),
    ADD COLUMN resolved_at        TIMESTAMP,
    ADD COLUMN resolution_note    TEXT,
    ADD COLUMN beneficiary_user_id UUID;
