-- V58__negotiation_threads.sql
CREATE TABLE negotiation_threads (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  package_request_id UUID NOT NULL REFERENCES package_requests(id),
  traveler_id UUID NOT NULL REFERENCES users(id),
  traveler_announcement_id UUID REFERENCES announcements(id),

  traveler_travel_date DATE NOT NULL,
  traveler_available_kg NUMERIC(5,2) NOT NULL,

  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  current_price_eur NUMERIC(10,2) NOT NULL,
  rounds_count SMALLINT NOT NULL DEFAULT 1,
  last_activity_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  payment_intent_id VARCHAR(255),

  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ,

  CONSTRAINT chk_neg_thread_rounds CHECK (rounds_count >= 0),
  CONSTRAINT chk_neg_thread_kg CHECK (traveler_available_kg > 0),
  CONSTRAINT chk_neg_thread_price CHECK (current_price_eur > 0 AND current_price_eur <= 500),
  CONSTRAINT chk_neg_thread_status CHECK (
    status IN ('OPEN', 'ACCEPTED', 'REJECTED', 'AUTO_REJECTED', 'EXPIRED')
  )
);

CREATE UNIQUE INDEX idx_neg_threads_unique_active
  ON negotiation_threads (package_request_id, traveler_id)
  WHERE deleted_at IS NULL;

CREATE INDEX idx_neg_threads_request ON negotiation_threads (package_request_id, status);
CREATE INDEX idx_neg_threads_traveler ON negotiation_threads (traveler_id, status);
CREATE INDEX idx_neg_threads_inactive
  ON negotiation_threads (last_activity_at) WHERE status = 'OPEN' AND deleted_at IS NULL;
