-- Rien ne transitionnait CancellationEntity.no_show_status quand un litige lié
-- (SENDER_NO_SHOW_CONTESTED / RECIPIENT_NO_SHOW*_CONTESTED / TRAVELER_DELIVERY_NO_SHOW*_CONTESTED)
-- était résolu par l'admin : la bannière app restait bloquée sur PENDING_CONFIRMATION/CONTESTED
-- indéfiniment. Ajoute un statut terminal RESOLVED, posé par AdminDisputesController à la
-- résolution du litige lié.

ALTER TABLE cancellations DROP CONSTRAINT cancellations_no_show_status_check;

ALTER TABLE cancellations ADD CONSTRAINT cancellations_no_show_status_check
    CHECK (no_show_status IN ('CONFIRMED', 'PENDING_CONFIRMATION', 'CONTESTED', 'RESOLVED'));
