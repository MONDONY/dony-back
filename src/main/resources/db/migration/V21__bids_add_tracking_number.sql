ALTER TABLE bids ADD COLUMN tracking_number VARCHAR(12);
CREATE UNIQUE INDEX uq_bids_tracking_number ON bids (tracking_number) WHERE tracking_number IS NOT NULL;
