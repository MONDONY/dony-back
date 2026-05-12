CREATE TABLE referral_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    code VARCHAR(20) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_referral_codes_code ON referral_codes(code);

CREATE TABLE referral_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    referrer_user_id UUID NOT NULL REFERENCES users(id),
    referee_user_id UUID REFERENCES users(id),
    referee_phone VARCHAR(20),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    code_used VARCHAR(20),
    rewarded_at TIMESTAMP,
    credit_amount_cents INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);
CREATE INDEX idx_referral_invitations_referrer ON referral_invitations(referrer_user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_referral_invitations_referee ON referral_invitations(referee_user_id) WHERE deleted_at IS NULL AND referee_user_id IS NOT NULL;

CREATE TABLE user_credits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    amount_cents INTEGER NOT NULL,
    source VARCHAR(50) NOT NULL,
    reference_id UUID,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_credits_user ON user_credits(user_id);
