CREATE TABLE stripe_event_inbox (
    event_id        VARCHAR(255) NOT NULL,
    source          VARCHAR(16)  NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'RECEIVED',
    retry_count     INT          NOT NULL DEFAULT 0,
    last_error      TEXT,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ,
    CONSTRAINT pk_stripe_event_inbox PRIMARY KEY (event_id)
);

CREATE INDEX idx_stripe_event_inbox_pending
    ON stripe_event_inbox (next_attempt_at)
    WHERE status IN ('RECEIVED', 'FAILED');

-- Reprise de l'historique : les events déjà traités ne seront pas rejoués
-- processed_stripe_events est droppée dans V86 (après suppression de l'entité Java)
INSERT INTO stripe_event_inbox
    (event_id, source, event_type, payload, status, received_at, processed_at)
SELECT event_id, 'PAYMENTS', 'legacy.unknown', '{}', 'PROCESSED', processed_at, processed_at
FROM processed_stripe_events
ON CONFLICT (event_id) DO NOTHING;
