-- Story 5.2 — reject reason
-- Story 5.3 — handover window
-- Story 5.4 — presence confirmation + H-2 alert tracking

ALTER TABLE bids
    ADD COLUMN rejection_reason        TEXT,
    ADD COLUMN handover_location       TEXT,
    ADD COLUMN handover_window_start   TIMESTAMP WITH TIME ZONE,
    ADD COLUMN handover_window_end     TIMESTAMP WITH TIME ZONE,
    ADD COLUMN voyageur_confirmed      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN h2_alert_sent_at        TIMESTAMP WITH TIME ZONE;
