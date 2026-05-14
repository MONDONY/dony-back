-- V59__negotiation_messages.sql
CREATE TABLE negotiation_messages (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  thread_id UUID NOT NULL REFERENCES negotiation_threads(id),
  from_user_id UUID NOT NULL REFERENCES users(id),
  kind VARCHAR(20) NOT NULL,
  proposed_price_eur NUMERIC(10,2),
  body VARCHAR(280),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT chk_neg_msg_kind CHECK (
    kind IN ('PROPOSAL', 'COUNTER', 'ACCEPT', 'REJECT')
  ),
  CONSTRAINT chk_neg_msg_price CHECK (
    proposed_price_eur IS NULL OR (proposed_price_eur > 0 AND proposed_price_eur <= 500)
  ),
  CONSTRAINT chk_neg_msg_price_required CHECK (
    (kind IN ('PROPOSAL', 'COUNTER') AND proposed_price_eur IS NOT NULL)
    OR kind IN ('ACCEPT', 'REJECT')
  )
);

CREATE INDEX idx_neg_messages_thread ON negotiation_messages (thread_id, created_at);

-- Reuse the existing immutability function from audit_log if present, otherwise define
CREATE OR REPLACE FUNCTION raise_immutability_violation_neg_msg()
RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'negotiation_messages is append-only — UPDATE/DELETE forbidden';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_neg_messages_immutable
  BEFORE UPDATE OR DELETE ON negotiation_messages
  FOR EACH ROW EXECUTE FUNCTION raise_immutability_violation_neg_msg();
