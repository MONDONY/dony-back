-- Replace simple UNIQUE constraints with partial unique indexes
-- that exclude soft-deleted rows, so soft-delete + recreate works correctly.

ALTER TABLE conversations DROP CONSTRAINT conversations_bid_id_key;
ALTER TABLE conversations DROP CONSTRAINT conversations_firestore_conversation_id_key;

CREATE UNIQUE INDEX conversations_bid_id_active_idx
    ON conversations (bid_id)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX conversations_firestore_id_active_idx
    ON conversations (firestore_conversation_id)
    WHERE deleted_at IS NULL;
