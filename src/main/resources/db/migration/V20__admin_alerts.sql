-- Story 6.5 — Admin alerts table for operator fallback (J+48 escrow timeout, etc.)
CREATE TABLE admin_alerts (
    id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    type       VARCHAR(60)  NOT NULL,
    payload    JSONB,
    resolved   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_admin_alerts PRIMARY KEY (id)
);

CREATE INDEX idx_admin_alerts_type     ON admin_alerts (type);
CREATE INDEX idx_admin_alerts_resolved ON admin_alerts (resolved);
