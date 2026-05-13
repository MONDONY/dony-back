-- Backfill tracking artefacts for Bids materialised from a NegotiationThread
-- before V67 (the listener didn't generate qr_token / tracking_number /
-- tracking_token, so the sender's bid detail screen rendered no QR card).
--
-- We generate one tracking_number per row using a row-by-row loop so the
-- randomness is per-bid (a single SQL expression with random() would produce
-- the same value for every row inside the same UPDATE).

DO $$
DECLARE
    r RECORD;
    chars TEXT := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
    new_tracking TEXT;
    attempts INT;
BEGIN
    FOR r IN
        SELECT id FROM bids
        WHERE linked_negotiation_thread_id IS NOT NULL
          AND status = 'ACCEPTED'
          AND tracking_number IS NULL
    LOOP
        attempts := 0;
        LOOP
            new_tracking := 'DON-';
            FOR i IN 1..8 LOOP
                new_tracking := new_tracking || substr(chars, 1 + floor(random() * length(chars))::int, 1);
            END LOOP;
            EXIT WHEN NOT EXISTS (SELECT 1 FROM bids WHERE tracking_number = new_tracking);
            attempts := attempts + 1;
            IF attempts > 5 THEN
                RAISE EXCEPTION 'Could not generate unique tracking_number after 5 attempts for bid %', r.id;
            END IF;
        END LOOP;

        UPDATE bids
        SET tracking_number = new_tracking,
            tracking_token  = COALESCE(tracking_token, gen_random_uuid()::text),
            qr_token        = COALESCE(qr_token, gen_random_uuid()::text)
        WHERE id = r.id;
    END LOOP;
END $$;
