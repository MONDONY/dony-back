-- V112__create_wallet_accounts.sql
CREATE TABLE wallet_accounts (
    id          UUID                     NOT NULL DEFAULT gen_random_uuid(),
    user_id     UUID                     NOT NULL,
    balance     DECIMAL(10,2)            NOT NULL DEFAULT 0,
    currency    VARCHAR(3)               NOT NULL DEFAULT 'EUR',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT wallet_accounts_pkey PRIMARY KEY (id),
    CONSTRAINT wallet_accounts_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT wallet_accounts_user_id_unique UNIQUE (user_id),
    CONSTRAINT wallet_accounts_balance_non_negative CHECK (balance >= 0)
);

CREATE INDEX idx_wallet_accounts_user_id ON wallet_accounts (user_id);
