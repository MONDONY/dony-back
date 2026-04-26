ALTER TABLE bids ADD COLUMN tracking_token VARCHAR(36);
CREATE UNIQUE INDEX uq_bids_tracking_token ON bids (tracking_token) WHERE tracking_token IS NOT NULL;
