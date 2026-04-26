-- Story 8.3 — SMS fallback tracking
ALTER TABLE notifications
    ADD COLUMN is_critical  BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN acked_at     TIMESTAMPTZ,
    ADD COLUMN sms_sent_at  TIMESTAMPTZ;

-- Partial index: only unacknowledged critical notifications that haven't received an SMS yet
CREATE INDEX idx_notifications_sms_fallback
    ON notifications (created_at)
    WHERE is_critical = TRUE
      AND acked_at    IS NULL
      AND sms_sent_at IS NULL
      AND deleted_at  IS NULL;