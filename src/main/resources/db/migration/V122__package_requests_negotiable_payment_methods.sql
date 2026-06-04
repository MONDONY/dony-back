ALTER TABLE package_requests
    ADD COLUMN negotiable BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN accepted_payment_methods VARCHAR NOT NULL DEFAULT '{STRIPE}';
