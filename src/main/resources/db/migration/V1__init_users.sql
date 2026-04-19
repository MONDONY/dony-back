-- V1: Core users table and roles

CREATE TABLE users (
    id              UUID                     NOT NULL DEFAULT gen_random_uuid(),
    firebase_uid    VARCHAR(128)             NOT NULL,
    phone_number    VARCHAR(20),
    email           VARCHAR(255),
    status          VARCHAR(20)              NOT NULL DEFAULT 'ACTIVE',
    fcm_token       VARCHAR(512),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_firebase_uid UNIQUE (firebase_uid)
);

CREATE INDEX idx_users_firebase_uid ON users (firebase_uid);
CREATE INDEX idx_users_phone_number ON users (phone_number);
CREATE INDEX idx_users_deleted_at   ON users (deleted_at);

CREATE TABLE user_roles (
    user_id UUID        NOT NULL,
    role    VARCHAR(30) NOT NULL,

    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
