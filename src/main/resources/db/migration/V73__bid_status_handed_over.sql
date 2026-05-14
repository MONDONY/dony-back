-- bid.status est VARCHAR(20) via EnumType.STRING — aucune modification DDL requise.
-- Ajout de HANDED_OVER dans l'enum Java BidStatus.
-- Transition : ACCEPTED → HANDED_OVER lors du scan TrackingEventType.DEPART par le voyageur
--             (colis physiquement dans la valise, confirmation de remise).
-- BidStatus: PENDING | AWAITING_PAYMENT | PAYMENT_ESCROWED | ACCEPTED | HANDED_OVER | IN_TRANSIT | REJECTED | CANCELLED | COMPLETED | NO_SHOW | PARCEL_REFUSED | EXPIRED
COMMENT ON COLUMN bids.status IS 'BidStatus: PENDING | AWAITING_PAYMENT | PAYMENT_ESCROWED | ACCEPTED | HANDED_OVER | IN_TRANSIT | REJECTED | CANCELLED | COMPLETED | NO_SHOW | PARCEL_REFUSED | EXPIRED';
