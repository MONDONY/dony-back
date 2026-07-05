-- V153 : add severity + resolved_at to admin_alerts
ALTER TABLE admin_alerts
    ADD COLUMN IF NOT EXISTS severity    VARCHAR(10)              NOT NULL DEFAULT 'INFO',
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMP WITH TIME ZONE;
