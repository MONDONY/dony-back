-- Automation rules for PRO travelers
CREATE TABLE automation_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    traveler_id     UUID NOT NULL REFERENCES users(id),
    rule_type       VARCHAR(20) NOT NULL CHECK (rule_type IN ('PRESET', 'CUSTOM')),
    preset_rule_id  VARCHAR(50),
    name            VARCHAR(255) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    conditions      JSONB NOT NULL DEFAULT '[]',
    action          JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_automation_rules_traveler ON automation_rules(traveler_id) WHERE deleted_at IS NULL;
