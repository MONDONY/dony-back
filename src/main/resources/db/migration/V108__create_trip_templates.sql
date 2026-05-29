CREATE TABLE trip_templates (
    id                  UUID                        DEFAULT gen_random_uuid() NOT NULL,
    user_id             UUID                        NOT NULL,
    label               VARCHAR(60)                 NOT NULL,
    emoji               VARCHAR(8),
    departure_city      VARCHAR(100)                NOT NULL,
    departure_lat       DOUBLE PRECISION,
    departure_lng       DOUBLE PRECISION,
    arrival_city        VARCHAR(100)                NOT NULL,
    arrival_lat         DOUBLE PRECISION,
    arrival_lng         DOUBLE PRECISION,
    transport_mode      VARCHAR(20)                 NOT NULL DEFAULT 'PLANE',
    capacity_unit       VARCHAR(20)                 NOT NULL DEFAULT 'SUITCASE_23KG',
    available_kg        INTEGER                     NOT NULL DEFAULT 23,
    price_per_kg        NUMERIC(10, 2)              NOT NULL,
    accepted_categories TEXT,
    created_at          TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT trip_templates_pkey PRIMARY KEY (id),
    CONSTRAINT trip_templates_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_trip_templates_user_id ON trip_templates (user_id);
