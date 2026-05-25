CREATE TABLE traveler_subscriptions (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id   UUID        NOT NULL REFERENCES users(id),
    traveler_id UUID        NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_traveler_sub UNIQUE (sender_id, traveler_id)
);

CREATE INDEX idx_traveler_sub_sender   ON traveler_subscriptions(sender_id);
CREATE INDEX idx_traveler_sub_traveler ON traveler_subscriptions(traveler_id);
