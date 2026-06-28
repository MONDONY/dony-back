-- V159: Corrige les trajets dédiés dont available_kg a été mal recalculé
-- par V158. Les trajets dédiés (linked_package_request_id IS NOT NULL)
-- utilisent available_kg = 0 par conception (la capacité est réservée à
-- l'expéditeur jusqu'à ouverture du surplus via openSurplus).
-- V158 a recalculé available_kg = total_kg − bids_acceptés pour ces trajets,
-- ce qui est incorrect quand aucun bid n'existait encore (ex: paiement échoué,
-- bid non matérialisé). On remet available_kg = 0 uniquement pour :
--   - les trajets dédiés sans surplus ouvert (surplus_published = false),
--   - qui n'ont pas de bid actif (available_kg incorrectement > 0 par V158).

UPDATE announcements
SET available_kg = 0
WHERE linked_package_request_id IS NOT NULL
  AND surplus_published = false
  AND deleted_at IS NULL
  AND available_kg > 0
  AND NOT EXISTS (
      SELECT 1
      FROM bids b
      WHERE b.announcement_id = announcements.id
        AND b.status IN ('ACCEPTED', 'HANDED_OVER', 'IN_TRANSIT', 'COMPLETED')
        AND b.deleted_at IS NULL
  );

-- Remettre ACTIVE les trajets dédiés passés en FULL à tort par V158
-- (available_kg = 0 mais aucun bid actif → ils étaient ACTIVE par design).
UPDATE announcements
SET status = 'ACTIVE'
WHERE linked_package_request_id IS NOT NULL
  AND surplus_published = false
  AND deleted_at IS NULL
  AND status = 'FULL'
  AND available_kg = 0
  AND NOT EXISTS (
      SELECT 1
      FROM bids b
      WHERE b.announcement_id = announcements.id
        AND b.status IN ('ACCEPTED', 'HANDED_OVER', 'IN_TRANSIT', 'COMPLETED')
        AND b.deleted_at IS NULL
  );
