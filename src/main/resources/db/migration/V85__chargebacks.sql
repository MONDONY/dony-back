CREATE TABLE chargebacks (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    stripe_dispute_id VARCHAR(255) NOT NULL,
    stripe_charge_id  VARCHAR(255),
    payment_id        UUID,
    bid_id            UUID,
    amount            BIGINT      NOT NULL,
    currency          VARCHAR(8)  NOT NULL,
    reason            VARCHAR(64),
    status            VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    outcome           VARCHAR(16),
    opened_at         TIMESTAMPTZ NOT NULL,
    resolved_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ,
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT pk_chargebacks PRIMARY KEY (id),
    CONSTRAINT uq_chargebacks_dispute UNIQUE (stripe_dispute_id),
    CONSTRAINT fk_chargebacks_payment FOREIGN KEY (payment_id) REFERENCES payments(id)
);

ALTER TABLE payments ADD COLUMN disputed BOOLEAN NOT NULL DEFAULT FALSE;
