-- V36: align legacy TIMESTAMP (without time zone) columns to TIMESTAMPTZ.
-- Project convention is UTC end-to-end; the columns added by V23 and V33 used
-- the timezone-naive type which silently shifts on the JDBC client zone.
-- We assume the existing values are already UTC (the application has always
-- written via LocalDateTime.now(ZoneOffset.UTC)).

ALTER TABLE bids
    ALTER COLUMN confirmation_code_expiry TYPE TIMESTAMP WITH TIME ZONE
    USING confirmation_code_expiry AT TIME ZONE 'UTC';

ALTER TABLE conversations
    ALTER COLUMN sender_deleted_at TYPE TIMESTAMP WITH TIME ZONE
    USING sender_deleted_at AT TIME ZONE 'UTC';

ALTER TABLE conversations
    ALTER COLUMN traveler_deleted_at TYPE TIMESTAMP WITH TIME ZONE
    USING traveler_deleted_at AT TIME ZONE 'UTC';
