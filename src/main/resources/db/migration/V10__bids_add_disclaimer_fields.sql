-- V10: Add disclaimer fields and content details to bids

ALTER TABLE bids
    ADD COLUMN content_category    VARCHAR(50),
    ADD COLUMN recipient_name      VARCHAR(200),
    ADD COLUMN recipient_phone     VARCHAR(30),
    ADD COLUMN disclaimer_signed_at  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN disclaimer_signed_ip  VARCHAR(45);
