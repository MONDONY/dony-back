ALTER TABLE announcements ADD COLUMN IF NOT EXISTS accepted_payment_methods TEXT[]
    NOT NULL DEFAULT ARRAY['STRIPE']::TEXT[];

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_announcements_payment_methods'
    ) THEN
        ALTER TABLE announcements ADD CONSTRAINT chk_announcements_payment_methods
            CHECK (accepted_payment_methods <@ ARRAY['STRIPE','CASH']::TEXT[]
                   AND array_length(accepted_payment_methods, 1) >= 1);
    END IF;
END
$$;
