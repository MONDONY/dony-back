-- Mémorise le sender « réservé » d'un trajet dédié (l'expéditeur de la
-- négociation pour qui le trajet a été créé). Sert à empêcher ce même sender
-- de refaire une demande sur le surplus de son propre trajet (il y a déjà
-- son colis réservé). NULL pour tous les trajets non dédiés.
ALTER TABLE announcements
    ADD COLUMN reserved_sender_id UUID;

ALTER TABLE announcements
    ADD CONSTRAINT fk_announcements_reserved_sender
    FOREIGN KEY (reserved_sender_id)
    REFERENCES users (id)
    ON DELETE SET NULL;
