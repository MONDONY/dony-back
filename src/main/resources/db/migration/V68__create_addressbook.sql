-- Pickup addresses (carnet d'adresses sender pour récupération)
CREATE TABLE pickup_addresses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    label VARCHAR(50) NOT NULL,
    street VARCHAR(255) NOT NULL,
    postal_code VARCHAR(20) NOT NULL,
    city VARCHAR(100) NOT NULL,
    country VARCHAR(2) NOT NULL DEFAULT 'FR',
    floor_apartment VARCHAR(50),
    instructions TEXT,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);
CREATE INDEX idx_pickup_addresses_user ON pickup_addresses(user_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_pickup_addresses_user_default
    ON pickup_addresses(user_id) WHERE is_default = TRUE AND deleted_at IS NULL;

-- Recipients (destinataires en Afrique)
CREATE TABLE recipients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    full_name VARCHAR(100) NOT NULL,
    relationship VARCHAR(50),
    phone_e164 VARCHAR(20) NOT NULL,
    whatsapp_e164 VARCHAR(20),
    street VARCHAR(255),
    city VARCHAR(100) NOT NULL,
    country VARCHAR(2) NOT NULL,
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);
CREATE INDEX idx_recipients_user ON recipients(user_id) WHERE deleted_at IS NULL;

-- Favorite travelers (voyageurs favoris)
CREATE TABLE favorite_travelers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    traveler_id UUID NOT NULL REFERENCES users(id),
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);
CREATE UNIQUE INDEX uq_favorite_travelers ON favorite_travelers(user_id, traveler_id)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_favorite_travelers_user ON favorite_travelers(user_id) WHERE deleted_at IS NULL;
