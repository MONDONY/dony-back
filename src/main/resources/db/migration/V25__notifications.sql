CREATE TABLE notifications (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id),
    type        VARCHAR(50) NOT NULL,
    title       VARCHAR(255) NOT NULL,
    body        TEXT,
    data        JSONB,
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    deleted_at  TIMESTAMPTZ
);

CREATE INDEX idx_notifications_user_id ON notifications(user_id);
CREATE INDEX idx_notifications_created_at ON notifications(user_id, created_at DESC);
