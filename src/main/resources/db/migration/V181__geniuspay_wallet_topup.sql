-- V181__geniuspay_wallet_topup.sql
-- Recharge du wallet interne du voyageur via GeniusPay (Wave/Orange/MTN).
-- amount_minor/fx_rate/rate_source : montant local GELÉ avant l'appel PSP
-- (règle R2, spec devise) -- jamais recalculé après coup.
CREATE TABLE public.wallet_topup_requests (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID          NOT NULL REFERENCES public.users(id),
    provider             VARCHAR(20)   NOT NULL CHECK (provider IN ('WAVE', 'ORANGE_MONEY', 'MTN_MONEY')),
    country_code         VARCHAR(5)    NOT NULL,
    phone_number         VARCHAR(30)   NOT NULL,
    amount_eur           DECIMAL(10,2) NOT NULL,
    currency             CHAR(3)       NOT NULL,
    amount_minor         BIGINT        NOT NULL,
    fx_rate              NUMERIC(18,8) NOT NULL,
    rate_source          VARCHAR(16)   NOT NULL,
    external_reference   VARCHAR(255)  UNIQUE,
    status               VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'EXPIRED')),
    failure_reason       TEXT,
    webhook_received_at  TIMESTAMPTZ,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    deleted_at           TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_wallet_topup_requests_user   ON public.wallet_topup_requests (user_id);
CREATE INDEX IF NOT EXISTS idx_wallet_topup_requests_status ON public.wallet_topup_requests (status) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_wallet_topup_requests_ext_ref ON public.wallet_topup_requests (external_reference) WHERE external_reference IS NOT NULL;

-- Anti-rejeu webhook GeniusPay : insert-first-then-process (même principe
-- que processed_stripe_events). Généralisé "geniuspay" plutôt que
-- "wallet_topup" au cas où GeniusPay servirait un jour un autre flux.
CREATE TABLE public.processed_geniuspay_events (
    external_reference  VARCHAR(255)  PRIMARY KEY,
    processed_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
