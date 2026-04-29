ALTER TABLE announcements ADD COLUMN description VARCHAR(500);

CREATE TABLE announcement_refused_types (
    announcement_id UUID NOT NULL REFERENCES announcements(id),
    content_type    VARCHAR(100) NOT NULL
);

CREATE INDEX idx_announcement_refused_types_announcement_id
    ON announcement_refused_types(announcement_id);
