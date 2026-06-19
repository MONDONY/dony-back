CREATE TABLE admin_users (
    id                    UUID PRIMARY KEY,
    firebase_uid          VARCHAR(128) NOT NULL UNIQUE,
    login                 VARCHAR(64)  NOT NULL UNIQUE,
    role                  VARCHAR(20)  NOT NULL CHECK (role IN ('SUPER_ADMIN','ADMIN','SUPPORT')),
    status                VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    must_change_password  BOOLEAN      NOT NULL DEFAULT TRUE,
    permission_overrides  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_by            UUID         NULL,
    last_login_at         TIMESTAMPTZ  NULL,
    created_at            TIMESTAMP    NOT NULL,
    updated_at            TIMESTAMP    NOT NULL,
    deleted_at            TIMESTAMP    NULL
);
CREATE INDEX idx_admin_users_role   ON admin_users(role);
CREATE INDEX idx_admin_users_status ON admin_users(status);
