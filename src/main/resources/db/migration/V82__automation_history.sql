-- Execution history for automation rules
CREATE TABLE automation_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    traveler_id     UUID NOT NULL REFERENCES users(id),
    rule_id         UUID REFERENCES automation_rules(id),
    rule_label      VARCHAR(255) NOT NULL,
    bid_id          UUID,
    trip_id         UUID,
    action_taken    VARCHAR(255) NOT NULL,
    result          VARCHAR(20) NOT NULL CHECK (result IN ('SUCCESS', 'FAILURE')),
    error_detail    TEXT,
    triggered_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_automation_history_traveler ON automation_history(traveler_id);
CREATE INDEX idx_automation_history_triggered ON automation_history(triggered_at DESC);
