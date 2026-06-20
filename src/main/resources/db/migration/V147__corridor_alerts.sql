-- Corridor saved-search alerts for travelers
CREATE TABLE corridor_alerts (
    id                      UUID                        DEFAULT gen_random_uuid() NOT NULL,
    traveler_id             UUID                        NOT NULL,
    departure_city          VARCHAR(100)                NOT NULL,
    departure_country_code  VARCHAR(2),
    arrival_city            VARCHAR(100)                NOT NULL,
    arrival_country_code    VARCHAR(2),
    date_from               DATE,
    date_to                 DATE,
    min_weight_kg           NUMERIC(5, 2),
    active                  BOOLEAN                     NOT NULL DEFAULT TRUE,
    last_notified_at        TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP WITH TIME ZONE     NOT NULL DEFAULT now(),
    deleted_at              TIMESTAMP WITH TIME ZONE,
    CONSTRAINT corridor_alerts_pkey PRIMARY KEY (id),
    CONSTRAINT corridor_alerts_traveler_id_fkey
        FOREIGN KEY (traveler_id) REFERENCES users (id)
);

CREATE INDEX idx_corridor_alerts_traveler  ON corridor_alerts (traveler_id);
CREATE INDEX idx_corridor_alerts_corridor  ON corridor_alerts (departure_city, arrival_city);

CREATE TABLE corridor_alert_content_categories (
    alert_id          UUID         NOT NULL REFERENCES corridor_alerts (id),
    content_category  VARCHAR(100) NOT NULL
);

CREATE INDEX idx_corridor_alert_content_categories_alert
    ON corridor_alert_content_categories (alert_id);

-- Digest push opt-out toggle (default ON)
ALTER TABLE user_notification_preferences
    ADD COLUMN push_corridor_alerts BOOLEAN NOT NULL DEFAULT TRUE;
