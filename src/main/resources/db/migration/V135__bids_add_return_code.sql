-- V135 : code de retour (annulation après remise, D7). Inverse du confirmation_code :
-- généré à l'annulation, détenu par l'expéditeur, saisi par le voyageur pour confirmer
-- la restitution du colis. Tout nullable (dégradation gracieuse : bids existants sans).
ALTER TABLE bids ADD COLUMN return_code          VARCHAR(6);
ALTER TABLE bids ADD COLUMN return_code_expiry   TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE bids ADD COLUMN return_code_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE bids ADD COLUMN return_deadline      TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE bids ADD COLUMN returned_at          TIMESTAMP WITHOUT TIME ZONE;

-- Index partiel consommé par le job J+3 (tranche D) : retours en attente.
CREATE INDEX idx_bids_return_deadline ON bids (return_deadline)
  WHERE returned_at IS NULL AND return_deadline IS NOT NULL;
