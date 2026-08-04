ALTER TABLE negotiation_threads
    ADD COLUMN sender_last_read_at TIMESTAMP,
    ADD COLUMN traveler_last_read_at TIMESTAMP;

UPDATE negotiation_threads
SET sender_last_read_at = CURRENT_TIMESTAMP,
    traveler_last_read_at = CURRENT_TIMESTAMP;
