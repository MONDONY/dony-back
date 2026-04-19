-- V5: Stripe escrow payments

CREATE TABLE payments (
    id                          UUID                     NOT NULL DEFAULT gen_random_uuid(),
    bid_id                      UUID                     NOT NULL,
    stripe_payment_intent_id    VARCHAR(255)             NOT NULL,
    amount                      DECIMAL(10, 2)           NOT NULL,
    commission_amount           DECIMAL(10, 2)           NOT NULL,
    status                      VARCHAR(30)              NOT NULL DEFAULT 'PENDING',
    escrow_released_at          TIMESTAMP WITH TIME ZONE,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT uq_payments_bid_id UNIQUE (bid_id),
    CONSTRAINT uq_payments_stripe_pi_id UNIQUE (stripe_payment_intent_id),
    CONSTRAINT fk_payments_bid FOREIGN KEY (bid_id) REFERENCES bids (id),
    CONSTRAINT chk_payments_amount CHECK (amount > 0),
    CONSTRAINT chk_payments_commission CHECK (commission_amount >= 0)
);

CREATE INDEX idx_payments_bid_id    ON payments (bid_id);
CREATE INDEX idx_payments_status    ON payments (status);
CREATE INDEX idx_payments_stripe_pi ON payments (stripe_payment_intent_id);
