-- Watermarks tracking when an announcement's available capacity crossed the
-- "freed" threshold for the "alert_capacity_free" automation rule.
CREATE TABLE automation_capacity_watermarks (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    announcement_id   UUID NOT NULL UNIQUE REFERENCES announcements(id),
    free_since        TIMESTAMP WITH TIME ZONE,
    last_alerted_at   TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
