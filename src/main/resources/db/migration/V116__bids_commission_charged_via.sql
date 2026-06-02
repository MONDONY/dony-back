-- Canal de prélèvement de la commission Dony (WALLET ou CARD) pour les bids hors escrow.
-- Permet de router le remboursement vers le bon support en cas d'annulation.
ALTER TABLE bids ADD COLUMN IF NOT EXISTS commission_charged_via VARCHAR(10)
    CHECK (commission_charged_via IN ('WALLET', 'CARD'));

-- Backfill : avant cette feature, la commission cash n'était JAMAIS prélevée via wallet
-- (la branche wallet de BidAcceptedEventListener était du code mort) — donc toute commission
-- déjà CHARGED l'a forcément été par carte. Ce backfill n'est correct que pour cet état initial.
UPDATE bids SET commission_charged_via = 'CARD'
 WHERE payment_method IN ('CASH', 'WAVE', 'ORANGE_MONEY')
   AND commission_status = 'CHARGED';
