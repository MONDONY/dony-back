-- V4: QR scan tracking events (supports offline sync)

CREATE TABLE tracking_events (
    id                  UUID                     NOT NULL DEFAULT gen_random_uuid(),
    bid_id              UUID                     NOT NULL,
    event_type          VARCHAR(30)              NOT NULL,
    scanned_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    -- GPS coordinates at scan time (written to photo EXIF too)
    gps_lat             DECIMAL(10, 8),
    gps_lon             DECIMAL(11, 8),
    photo_url           VARCHAR(1024),
    -- offlineTimestamp sent by Flutter when scan was done offline
    offline_timestamp   TIMESTAMP WITH TIME ZONE,
    synced_at           TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_tracking_events PRIMARY KEY (id),
    CONSTRAINT fk_tracking_events_bid FOREIGN KEY (bid_id) REFERENCES bids (id)
);

CREATE INDEX idx_tracking_events_bid_id     ON tracking_events (bid_id);
CREATE INDEX idx_tracking_events_event_type ON tracking_events (event_type);
CREATE INDEX idx_tracking_events_scanned_at ON tracking_events (scanned_at);
