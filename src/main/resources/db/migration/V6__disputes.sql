-- V6: Dispute resolution

CREATE TABLE disputes (
    id          UUID                     NOT NULL DEFAULT gen_random_uuid(),
    payment_id  UUID                     NOT NULL,
    reporter_id UUID                     NOT NULL,
    reason      TEXT                     NOT NULL,
    status      VARCHAR(20)              NOT NULL DEFAULT 'OPEN',
    resolution  TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_disputes PRIMARY KEY (id),
    CONSTRAINT fk_disputes_payment  FOREIGN KEY (payment_id)  REFERENCES payments (id),
    CONSTRAINT fk_disputes_reporter FOREIGN KEY (reporter_id) REFERENCES users (id)
);

CREATE INDEX idx_disputes_payment_id  ON disputes (payment_id);
CREATE INDEX idx_disputes_reporter_id ON disputes (reporter_id);
CREATE INDEX idx_disputes_status      ON disputes (status);
CREATE INDEX idx_disputes_deleted_at  ON disputes (deleted_at);
