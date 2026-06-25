-- V155 : add resolution fields to disputes
ALTER TABLE disputes
    ADD COLUMN IF NOT EXISTS resolution_type      VARCHAR(40),
    ADD COLUMN IF NOT EXISTS resolved_at          TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS resolution_note      TEXT,
    ADD COLUMN IF NOT EXISTS declared_value_eur   DECIMAL(10,2),
    ADD COLUMN IF NOT EXISTS beneficiary_user_id  UUID;
