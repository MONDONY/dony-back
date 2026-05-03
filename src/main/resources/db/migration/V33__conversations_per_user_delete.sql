-- Replace bilateral soft-delete with per-party deleted timestamps.
-- Each party can independently remove their view of the conversation.
-- When one party deletes, the other sees it in read-only mode.
ALTER TABLE conversations
    ADD COLUMN sender_deleted_at  TIMESTAMP,
    ADD COLUMN traveler_deleted_at TIMESTAMP;
