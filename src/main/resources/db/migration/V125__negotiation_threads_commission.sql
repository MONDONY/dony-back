ALTER TABLE negotiation_threads
    ADD COLUMN commission_status VARCHAR(20),
    ADD COLUMN commission_payment_intent_id VARCHAR(255),
    ADD COLUMN commission_charged_via VARCHAR(20);
