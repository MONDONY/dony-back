-- V8: Cancellations and rematch suggestions

CREATE TABLE cancellations (
    id              UUID                     NOT NULL DEFAULT gen_random_uuid(),
    bid_id          UUID                     NOT NULL,
    cancelled_by    UUID                     NOT NULL,
    reason          TEXT                     NOT NULL,
    refund_status   VARCHAR(20)              NOT NULL DEFAULT 'PENDING',
    rematch_status  VARCHAR(20)              NOT NULL DEFAULT 'NONE',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_cancellations PRIMARY KEY (id),
    CONSTRAINT uq_cancellations_bid_id UNIQUE (bid_id),
    CONSTRAINT fk_cancellations_bid          FOREIGN KEY (bid_id)       REFERENCES bids (id),
    CONSTRAINT fk_cancellations_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES users (id)
);

CREATE INDEX idx_cancellations_bid_id       ON cancellations (bid_id);
CREATE INDEX idx_cancellations_cancelled_by ON cancellations (cancelled_by);
CREATE INDEX idx_cancellations_refund       ON cancellations (refund_status);

CREATE TABLE rematch_suggestions (
    id              UUID                     NOT NULL DEFAULT gen_random_uuid(),
    cancellation_id UUID                     NOT NULL,
    announcement_id UUID                     NOT NULL,
    status          VARCHAR(20)              NOT NULL DEFAULT 'SUGGESTED',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_rematch_suggestions PRIMARY KEY (id),
    CONSTRAINT fk_rematch_cancellation   FOREIGN KEY (cancellation_id) REFERENCES cancellations (id),
    CONSTRAINT fk_rematch_announcement   FOREIGN KEY (announcement_id) REFERENCES announcements (id)
);

CREATE INDEX idx_rematch_cancellation_id ON rematch_suggestions (cancellation_id);
CREATE INDEX idx_rematch_status          ON rematch_suggestions (status);
