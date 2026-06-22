CREATE TABLE favorites (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id),
    target_type VARCHAR(32) NOT NULL,
    target_id   UUID NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX ux_favorites_active
    ON favorites (user_id, target_type, target_id)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_favorites_user_type
    ON favorites (user_id, target_type)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_favorites_target
    ON favorites (target_type, target_id)
    WHERE deleted_at IS NULL;
