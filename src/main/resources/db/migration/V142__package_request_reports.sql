-- Signalements (report) d'une demande d'envoi par un utilisateur.
-- 1 signalement max par (demande, reporter) — réitérer est idempotent côté service.
CREATE TABLE package_request_reports (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    package_request_id  UUID          NOT NULL REFERENCES package_requests(id),
    reporter_id         UUID          NOT NULL,
    reason              VARCHAR(50)   NOT NULL,
    details             VARCHAR(500),
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_prr_reporter UNIQUE (package_request_id, reporter_id)
);
CREATE INDEX idx_prr_report_request ON package_request_reports(package_request_id);
