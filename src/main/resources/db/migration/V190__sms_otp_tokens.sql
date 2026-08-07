CREATE TABLE sms_otp_tokens (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_number VARCHAR(20)  NOT NULL,
    code_hash    VARCHAR(60)  NOT NULL,
    expires_at   TIMESTAMP    NOT NULL,
    used_at      TIMESTAMP,
    attempts     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sms_otp_phone ON sms_otp_tokens (phone_number);
