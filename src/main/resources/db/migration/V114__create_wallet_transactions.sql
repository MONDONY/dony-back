-- V113__create_wallet_transactions.sql
CREATE TABLE wallet_transactions (
    id              UUID                     NOT NULL DEFAULT gen_random_uuid(),
    user_id         UUID                     NOT NULL,
    type            VARCHAR(30)              NOT NULL,
    amount          DECIMAL(10,2)            NOT NULL,
    balance_after   DECIMAL(10,2)            NOT NULL,
    bid_id          UUID,
    payment_ref     VARCHAR(255),
    idempotency_key VARCHAR(255),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT wallet_transactions_pkey PRIMARY KEY (id),
    CONSTRAINT wallet_transactions_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT wallet_transactions_bid_id_fkey FOREIGN KEY (bid_id) REFERENCES bids(id),
    CONSTRAINT wallet_transactions_type_check CHECK (
        type IN ('TOP_UP','BID_PAYMENT','COMMISSION_DEDUCTED','REFUND')
    ),
    CONSTRAINT wallet_transactions_idempotency_key_unique UNIQUE (idempotency_key)
);

CREATE INDEX idx_wallet_transactions_user_id ON wallet_transactions (user_id, created_at DESC);
