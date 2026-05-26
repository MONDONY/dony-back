CREATE TABLE delivery_addresses (
    id          UUID                        DEFAULT gen_random_uuid() NOT NULL,
    user_id     UUID                        NOT NULL,
    label       VARCHAR(50)                 NOT NULL,
    street      VARCHAR(255),
    city        VARCHAR(100)                NOT NULL,
    country     CHAR(2)                     NOT NULL DEFAULT 'SN',
    instructions TEXT,
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    is_default  BOOLEAN                     NOT NULL DEFAULT false,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT delivery_addresses_pkey PRIMARY KEY (id),
    CONSTRAINT delivery_addresses_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_delivery_addresses_user_id ON delivery_addresses (user_id);
