-- CANCELLED (fin de négociation avant paiement) doit être autorisé par la contrainte CHECK
-- du statut, sinon toute annulation échoue en PostgreSQL (la contrainte datait de V61).
ALTER TABLE negotiation_threads DROP CONSTRAINT chk_neg_thread_status;
ALTER TABLE negotiation_threads ADD CONSTRAINT chk_neg_thread_status CHECK (
  status IN ('OPEN','AWAITING_TRIP','AWAITING_PAYMENT','ACCEPTED','REJECTED','AUTO_REJECTED','EXPIRED','CANCELLED')
);
