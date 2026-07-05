-- V157 : create reports table (signalements génériques)
CREATE TABLE IF NOT EXISTS reports (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    target_type     VARCHAR(20)  NOT NULL,
    target_id       UUID         NOT NULL,
    reporter_id     UUID,
    reason          VARCHAR(100) NOT NULL,
    description     TEXT,
    status          VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    action_taken    VARCHAR(40),
    resolution_note TEXT,
    resolved_at     TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_reports PRIMARY KEY (id)
);
CREATE INDEX idx_reports_target_type ON reports (target_type);
CREATE INDEX idx_reports_status      ON reports (status);
CREATE INDEX idx_reports_deleted_at  ON reports (deleted_at);
