-- Add total_trips counter to users (incremented when a bid is marked completed)
ALTER TABLE users ADD COLUMN total_trips INTEGER NOT NULL DEFAULT 0;

-- Accepted content types per announcement (e.g. 'Vêtements', 'Médicaments')
CREATE TABLE announcement_accepted_types (
    announcement_id UUID NOT NULL REFERENCES announcements(id),
    content_type    VARCHAR(100) NOT NULL
);

CREATE INDEX idx_announcement_accepted_types_announcement_id
    ON announcement_accepted_types(announcement_id);
