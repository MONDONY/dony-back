-- V89: Retire le rôle TRAVELER aux users qui n'ont jamais complété KYC + Stripe Connect.
-- Aligne le système avec la décision "TRAVELER s'acquiert via upgrade explicite".
-- Seuls les users avec kyc_status=VERIFIED ET stripe_account_status=ONBOARDING_COMPLETE conservent TRAVELER.

-- 1. Audit log pour chaque retrait (avant le delete pour préserver les IDs)
INSERT INTO audit_log (entity_type, entity_id, action, actor_id, payload, created_at)
SELECT 'USER', u.id, 'USER_ROLE_REMOVED_MIGRATION', u.id,
       jsonb_build_object('role', 'TRAVELER', 'reason', 'V89_downgrade_incomplete_kyc_or_stripe'),
       NOW()
FROM users u
INNER JOIN user_roles ur ON ur.user_id = u.id AND ur.role = 'TRAVELER'
WHERE u.kyc_status != 'VERIFIED' OR u.stripe_account_status != 'ONBOARDING_COMPLETE';

-- 2. Downgrade effectif
DELETE FROM user_roles
WHERE role = 'TRAVELER'
  AND user_id IN (
    SELECT id FROM users
    WHERE kyc_status != 'VERIFIED'
       OR stripe_account_status != 'ONBOARDING_COMPLETE'
  );
