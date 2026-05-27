-- V112: Mobile Money (Wave + Orange Money) support
-- 1. Widen bids.payment_method column (VARCHAR(10) → VARCHAR(20))
-- 2. Update bids.payment_method CHECK constraint to include WAVE and ORANGE_MONEY
-- 3. Add mobile_money_phone and mobile_money_country_code to bids
-- 4. Create mobile_money_payments table

-- 1. Widen the bids.payment_method column
ALTER TABLE public.bids
    ALTER COLUMN payment_method TYPE VARCHAR(20);

-- 2. Drop old check constraint and replace with extended one
ALTER TABLE public.bids DROP CONSTRAINT IF EXISTS bids_payment_method_check;

ALTER TABLE public.bids
    ADD CONSTRAINT bids_payment_method_check
    CHECK (payment_method IN ('STRIPE', 'CASH', 'WAVE', 'ORANGE_MONEY'));

-- 3. Add mobile money fields to bids table
ALTER TABLE public.bids
    ADD COLUMN IF NOT EXISTS mobile_money_phone        VARCHAR(30),
    ADD COLUMN IF NOT EXISTS mobile_money_country_code VARCHAR(5);

-- 4. Create mobile_money_payments table
CREATE TABLE IF NOT EXISTS public.mobile_money_payments (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    bid_id               UUID          NOT NULL REFERENCES public.bids(id),
    traveler_id          UUID          NOT NULL REFERENCES public.users(id),
    provider             VARCHAR(20)   NOT NULL CHECK (provider IN ('WAVE', 'ORANGE_MONEY')),
    country_code         VARCHAR(5)    NOT NULL,
    phone_number         VARCHAR(30)   NOT NULL,
    amount               DECIMAL(10,2) NOT NULL,
    currency             VARCHAR(3)    NOT NULL DEFAULT 'XOF',
    external_reference   VARCHAR(255)  UNIQUE,
    payment_link         TEXT,
    status               VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'EXPIRED')),
    failure_reason       TEXT,
    expires_at           TIMESTAMPTZ,
    webhook_received_at  TIMESTAMPTZ,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_mm_payments_bid       ON public.mobile_money_payments (bid_id);
CREATE INDEX IF NOT EXISTS idx_mm_payments_status    ON public.mobile_money_payments (status) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_mm_payments_ext_ref   ON public.mobile_money_payments (external_reference) WHERE external_reference IS NOT NULL;
