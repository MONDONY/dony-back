ALTER TABLE bids
    ADD COLUMN confirmation_code          VARCHAR(6),
    ADD COLUMN confirmation_code_expiry   TIMESTAMP,
    ADD COLUMN confirmation_code_attempts INTEGER NOT NULL DEFAULT 0;
