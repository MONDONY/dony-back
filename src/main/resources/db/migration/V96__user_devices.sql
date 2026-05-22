CREATE TABLE user_devices (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id    VARCHAR(128) NOT NULL,
    device_name  VARCHAR(255) NOT NULL,
    platform     VARCHAR(10) NOT NULL CHECK (platform IN ('ios', 'android')),
    fcm_token    VARCHAR(512),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_device UNIQUE (user_id, device_id)
);

CREATE INDEX idx_user_devices_user_id ON user_devices(user_id);
