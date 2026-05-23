ALTER TABLE conversations
    ADD COLUMN sender_archived_at   TIMESTAMPTZ,
    ADD COLUMN traveler_archived_at TIMESTAMPTZ;
