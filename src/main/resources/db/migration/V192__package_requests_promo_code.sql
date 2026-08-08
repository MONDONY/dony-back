-- Code promo saisi par l'expéditeur à la création/publication de sa demande.
-- Copié sur negotiation_threads.promo_code (V191) dès qu'un thread démarre
-- (NegotiationService.start), pour être appliqué automatiquement au paiement
-- sans que l'expéditeur ait à le re-saisir.
ALTER TABLE package_requests
    ADD COLUMN promo_code VARCHAR(50);
