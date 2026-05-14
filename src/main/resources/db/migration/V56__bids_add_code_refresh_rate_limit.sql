ALTER TABLE bids
    ADD COLUMN confirmation_code_refresh_count        INTEGER   NOT NULL DEFAULT 0,
    ADD COLUMN confirmation_code_refresh_window_start TIMESTAMP;
