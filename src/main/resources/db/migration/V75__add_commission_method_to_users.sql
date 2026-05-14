ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_customer_id VARCHAR(255);

ALTER TABLE users ADD COLUMN IF NOT EXISTS commission_payment_method_id VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS commission_card_brand VARCHAR(20);
ALTER TABLE users ADD COLUMN IF NOT EXISTS commission_card_last4 VARCHAR(4);
ALTER TABLE users ADD COLUMN IF NOT EXISTS commission_card_exp_month INTEGER
    CHECK (commission_card_exp_month BETWEEN 1 AND 12);
ALTER TABLE users ADD COLUMN IF NOT EXISTS commission_card_exp_year INTEGER
    CHECK (commission_card_exp_year >= 2025);

CREATE INDEX IF NOT EXISTS idx_users_commission_pm ON users(commission_payment_method_id)
    WHERE commission_payment_method_id IS NOT NULL;
