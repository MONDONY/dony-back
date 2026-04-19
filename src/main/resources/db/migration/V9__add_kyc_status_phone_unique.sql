-- V9: Add kyc_status to users, unique constraint on phone_number

ALTER TABLE users ADD COLUMN IF NOT EXISTS kyc_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';

-- phone_number was indexed but not unique in V1
ALTER TABLE users ADD CONSTRAINT uq_users_phone_number UNIQUE (phone_number);
