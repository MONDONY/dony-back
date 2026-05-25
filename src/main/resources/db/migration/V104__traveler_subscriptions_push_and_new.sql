ALTER TABLE traveler_subscriptions
    ADD COLUMN push_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN has_new      BOOLEAN NOT NULL DEFAULT false;
