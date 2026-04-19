-- V2: KYC schema (isolated from public schema, AES-256 encrypted columns)

CREATE SCHEMA IF NOT EXISTS kyc_schema;

CREATE TABLE kyc_schema.kyc_verifications (
    id                              UUID                     NOT NULL DEFAULT gen_random_uuid(),
    user_id                         UUID                     NOT NULL,
    stripe_verification_session_id  VARCHAR(255),
    status                          VARCHAR(30)              NOT NULL DEFAULT 'PENDING',
    -- AES-256 encrypted at application level before persistence
    id_document_encrypted           TEXT,
    selfie_url                      VARCHAR(1024),
    rejection_reason                VARCHAR(512),
    created_at                      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at                      TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_kyc_verifications PRIMARY KEY (id),
    CONSTRAINT uq_kyc_user_id UNIQUE (user_id),
    CONSTRAINT fk_kyc_user FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE
);

CREATE INDEX idx_kyc_user_id    ON kyc_schema.kyc_verifications (user_id);
CREATE INDEX idx_kyc_status     ON kyc_schema.kyc_verifications (status);
CREATE INDEX idx_kyc_deleted_at ON kyc_schema.kyc_verifications (deleted_at);
