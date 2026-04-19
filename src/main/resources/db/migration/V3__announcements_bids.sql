-- V3: Announcements (traveler trips) and bids (sender requests)

CREATE TABLE announcements (
    id              UUID                     NOT NULL DEFAULT gen_random_uuid(),
    traveler_id     UUID                     NOT NULL,
    departure_city  VARCHAR(100)             NOT NULL,
    arrival_city    VARCHAR(100)             NOT NULL,
    departure_date  DATE                     NOT NULL,
    available_kg    DECIMAL(5, 2)            NOT NULL,
    price_per_kg    DECIMAL(10, 2)           NOT NULL,
    status          VARCHAR(20)              NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_announcements PRIMARY KEY (id),
    CONSTRAINT fk_announcements_traveler FOREIGN KEY (traveler_id) REFERENCES users (id),
    CONSTRAINT chk_announcements_available_kg CHECK (available_kg > 0),
    CONSTRAINT chk_announcements_price_per_kg CHECK (price_per_kg > 0)
);

CREATE INDEX idx_announcements_traveler_id  ON announcements (traveler_id);
CREATE INDEX idx_announcements_status       ON announcements (status);
CREATE INDEX idx_announcements_departure    ON announcements (departure_city, arrival_city, departure_date);
CREATE INDEX idx_announcements_deleted_at   ON announcements (deleted_at);

CREATE TABLE bids (
    id              UUID                     NOT NULL DEFAULT gen_random_uuid(),
    announcement_id UUID                     NOT NULL,
    sender_id       UUID                     NOT NULL,
    weight_kg       DECIMAL(5, 2)            NOT NULL,
    declared_value_eur DECIMAL(10, 2)        NOT NULL,
    description     TEXT,
    status          VARCHAR(20)              NOT NULL DEFAULT 'PENDING',
    qr_token        VARCHAR(255),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_bids PRIMARY KEY (id),
    CONSTRAINT fk_bids_announcement FOREIGN KEY (announcement_id) REFERENCES announcements (id),
    CONSTRAINT fk_bids_sender       FOREIGN KEY (sender_id) REFERENCES users (id),
    CONSTRAINT chk_bids_weight_kg   CHECK (weight_kg > 0),
    CONSTRAINT chk_bids_declared_value CHECK (declared_value_eur > 0 AND declared_value_eur <= 500),
    CONSTRAINT uq_bids_qr_token     UNIQUE (qr_token)
);

CREATE INDEX idx_bids_announcement_id ON bids (announcement_id);
CREATE INDEX idx_bids_sender_id       ON bids (sender_id);
CREATE INDEX idx_bids_status          ON bids (status);
CREATE INDEX idx_bids_deleted_at      ON bids (deleted_at);
