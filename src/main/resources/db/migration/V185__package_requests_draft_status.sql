-- Ajoute le statut DRAFT aux demandes d'envoi.
--
-- Une demande pouvait seulement naître publiée : la contrainte CHECK posée en
-- V57 n'admet pas d'état antérieur à la publication. DRAFT ouvre deux usages —
-- préparer une demande et la publier plus tard, et dépublier une demande sans
-- l'annuler (annuler est terminal).
--
-- La contrainte est recréée plutôt qu'assouplie : garder une liste fermée de
-- statuts valides est ce qui protège la colonne d'une faute de frappe côté
-- applicatif.

ALTER TABLE package_requests
  DROP CONSTRAINT IF EXISTS chk_pkg_req_status;

ALTER TABLE package_requests
  ADD CONSTRAINT chk_pkg_req_status CHECK (
    status IN ('DRAFT', 'OPEN', 'NEGOTIATING', 'ACCEPTED',
               'EXPIRED', 'CANCELLED', 'COMPLETED')
  );
