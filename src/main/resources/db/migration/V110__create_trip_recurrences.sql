CREATE TABLE trip_recurrences (
    id                  UUID                        DEFAULT gen_random_uuid() NOT NULL,
    user_id             UUID                        NOT NULL,
    source_template_id  UUID,
    departure_city      VARCHAR(100)                NOT NULL,
    arrival_city        VARCHAR(100)                NOT NULL,
    transport_mode      VARCHAR(20)                 NOT NULL DEFAULT 'PLANE',
    capacity_unit       VARCHAR(20)                 NOT NULL DEFAULT 'SUITCASE_23KG',
    available_kg        DOUBLE PRECISION            NOT NULL,
    price_per_kg        DOUBLE PRECISION            NOT NULL,
    accepted_categories TEXT,
    pickup_label        VARCHAR(255)                NOT NULL,
    pickup_lat          DOUBLE PRECISION            NOT NULL,
    pickup_lng          DOUBLE PRECISION            NOT NULL,
    delivery_label      VARCHAR(255)                NOT NULL,
    delivery_lat        DOUBLE PRECISION            NOT NULL,
    delivery_lng        DOUBLE PRECISION            NOT NULL,
    departure_time      TIME,
    weekdays            VARCHAR(7)                  NOT NULL,
    horizon_days        INTEGER                     NOT NULL DEFAULT 14,
    active              BOOLEAN                     NOT NULL DEFAULT true,
    last_generated_date DATE,
    created_at          TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT trip_recurrences_pkey PRIMARY KEY (id),
    CONSTRAINT trip_recurrences_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_trip_recurrences_user_id ON trip_recurrences (user_id);
CREATE INDEX idx_trip_recurrences_active ON trip_recurrences (active) WHERE deleted_at IS NULL;
