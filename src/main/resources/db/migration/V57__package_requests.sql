-- V57__package_requests.sql
CREATE TABLE package_requests (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  sender_id UUID NOT NULL REFERENCES users(id),

  departure_city VARCHAR(100) NOT NULL,
  arrival_city VARCHAR(100) NOT NULL,
  desired_date DATE NOT NULL,
  date_tolerance_days SMALLINT NOT NULL DEFAULT 2,

  weight_kg NUMERIC(5,2) NOT NULL,
  parcel_size VARCHAR(10) NOT NULL,
  content_category VARCHAR(50) NOT NULL,
  description VARCHAR(500),
  target_price_eur NUMERIC(10,2),
  photo_url VARCHAR(500),

  pickup_neighborhood VARCHAR(100),
  delivery_neighborhood VARCHAR(100),

  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',

  -- Renseignés après acceptation
  pickup_address_label VARCHAR(255),
  pickup_lat NUMERIC(9,6),
  pickup_lng NUMERIC(9,6),
  delivery_address_label VARCHAR(255),
  delivery_lat NUMERIC(9,6),
  delivery_lng NUMERIC(9,6),
  recipient_name VARCHAR(100),
  recipient_phone VARCHAR(30),
  declared_value_eur NUMERIC(10,2),
  disclaimer_signed_at TIMESTAMPTZ,
  disclaimer_signed_ip INET,

  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ,

  CONSTRAINT chk_pkg_req_declared_value
    CHECK (declared_value_eur IS NULL OR declared_value_eur BETWEEN 0 AND 500),
  CONSTRAINT chk_pkg_req_target_price
    CHECK (target_price_eur IS NULL OR target_price_eur BETWEEN 0 AND 500),
  CONSTRAINT chk_pkg_req_weight CHECK (weight_kg BETWEEN 0.5 AND 30),
  CONSTRAINT chk_pkg_req_tolerance CHECK (date_tolerance_days BETWEEN 0 AND 7),
  CONSTRAINT chk_pkg_req_corridor CHECK (lower(departure_city) <> lower(arrival_city)),
  CONSTRAINT chk_pkg_req_size CHECK (parcel_size IN ('SMALL', 'MEDIUM', 'LARGE')),
  CONSTRAINT chk_pkg_req_status CHECK (
    status IN ('OPEN', 'NEGOTIATING', 'ACCEPTED', 'EXPIRED', 'CANCELLED', 'COMPLETED')
  )
);

CREATE INDEX idx_package_requests_search
  ON package_requests (status, departure_city, arrival_city, desired_date)
  WHERE deleted_at IS NULL;

CREATE INDEX idx_package_requests_sender
  ON package_requests (sender_id, status)
  WHERE deleted_at IS NULL;
