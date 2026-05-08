-- V10: Add NOT_STARTED as initial KYC status for new users
-- Users who registered before this migration have PENDING but never initiated KYC
-- → reclassify them as NOT_STARTED so the UI shows "Vérifier" instead of "En cours"

-- Update default for future inserts
ALTER TABLE users ALTER COLUMN kyc_status SET DEFAULT 'NOT_STARTED';

-- Reclassify existing PENDING users who have no KYC verification record as NOT_STARTED
UPDATE users u
SET kyc_status = 'NOT_STARTED'
WHERE u.kyc_status = 'PENDING'
  AND NOT EXISTS (
      SELECT 1 FROM kyc_schema.kyc_verifications kv WHERE kv.user_id = u.id
  );
