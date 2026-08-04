ALTER TABLE negotiation_threads
    ADD COLUMN sender_last_read_at TIMESTAMP,
    ADD COLUMN traveler_last_read_at TIMESTAMP;

-- Ne backfille que les threads terminés (plus de badge "non lu" à préserver).
-- Les threads actifs (OPEN/AWAITING_TRIP/AWAITING_PAYMENT) restent NULL : les
-- marquer "lus maintenant" effacerait silencieusement un vrai non-lu existant.
UPDATE negotiation_threads
SET sender_last_read_at = CURRENT_TIMESTAMP,
    traveler_last_read_at = CURRENT_TIMESTAMP
WHERE status NOT IN ('OPEN', 'AWAITING_TRIP', 'AWAITING_PAYMENT');
