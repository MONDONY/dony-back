-- V7: Immutable audit log + trigger

CREATE TABLE audit_log (
    id          BIGSERIAL                NOT NULL,
    entity_type VARCHAR(50)              NOT NULL,
    entity_id   UUID,
    action      VARCHAR(50)              NOT NULL,
    actor_id    UUID,
    payload     JSONB,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_audit_log PRIMARY KEY (id)
);

CREATE INDEX idx_audit_log_entity  ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_log_actor   ON audit_log (actor_id);
CREATE INDEX idx_audit_log_action  ON audit_log (action);
CREATE INDEX idx_audit_log_created ON audit_log (created_at);

-- Trigger: prevent any UPDATE or DELETE on audit_log (append-only)
CREATE OR REPLACE FUNCTION prevent_audit_log_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Les entrées audit_log sont immuables — opération % interdite', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_modification();
