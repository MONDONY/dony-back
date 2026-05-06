-- Align KycVerificationStatus: REQUIRES_INPUT -> REJECTED (matches KycStatus enum on UserEntity)
UPDATE kyc_schema.kyc_verifications
SET status = 'REJECTED'
WHERE status = 'REQUIRES_INPUT';

-- Drop dead columns: never written, Stripe is source of truth for document/selfie data
ALTER TABLE kyc_schema.kyc_verifications
    DROP COLUMN IF EXISTS id_document_encrypted,
    DROP COLUMN IF EXISTS selfie_url;
