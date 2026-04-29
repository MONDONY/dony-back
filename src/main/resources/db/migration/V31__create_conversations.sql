CREATE TABLE conversations (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bid_id                    UUID NOT NULL UNIQUE REFERENCES bids(id),
    sender_id                 UUID NOT NULL REFERENCES users(id),
    traveler_id               UUID NOT NULL REFERENCES users(id),
    firestore_conversation_id VARCHAR(255) NOT NULL UNIQUE,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at                TIMESTAMPTZ
);

CREATE INDEX idx_conversations_sender_id   ON conversations(sender_id)   WHERE deleted_at IS NULL;
CREATE INDEX idx_conversations_traveler_id ON conversations(traveler_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_conversations_bid_id      ON conversations(bid_id);
