-- V95__user_notification_preferences.sql
CREATE TABLE user_notification_preferences (
    user_id                     UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    push_activity_bids          BOOLEAN NOT NULL DEFAULT TRUE,
    push_activity_negotiations  BOOLEAN NOT NULL DEFAULT TRUE,
    push_messages               BOOLEAN NOT NULL DEFAULT TRUE,
    push_trip_reminder          BOOLEAN NOT NULL DEFAULT TRUE,
    push_promo                  BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
