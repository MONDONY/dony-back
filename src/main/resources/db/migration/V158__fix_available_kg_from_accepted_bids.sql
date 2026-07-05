-- V158: Recalcule available_kg pour toutes les annonces dont le flux
-- package_request a matérialisé des bids ACCEPTED sans décrémenter
-- la capacité (bug corrigé dans ThreadAcceptedBidListener).
--
-- Les statuts retenus comme « consommant » de la capacité sont ceux
-- qui ne font PAS l'objet d'un remboursement kg (REJECTED, CANCELLED,
-- NO_SHOW, PARCEL_REFUSED, EXPIRED remettent la capacité dans BidService).

UPDATE announcements a
SET available_kg = GREATEST(
    a.total_kg - COALESCE((
        SELECT SUM(b.weight_kg)
        FROM bids b
        WHERE b.announcement_id = a.id
          AND b.status IN (
              'ACCEPTED', 'PAYMENT_ESCROWED',
              'HANDED_OVER', 'IN_TRANSIT', 'COMPLETED'
          )
          AND b.weight_kg IS NOT NULL
          AND b.deleted_at IS NULL
    ), 0),
    0
)
WHERE a.deleted_at IS NULL
  AND a.capacity_unit != 'KG_FREE';

-- Passer les annonces épuisées en FULL si ce n'est pas déjà fait.
UPDATE announcements
SET status = 'FULL'
WHERE deleted_at IS NULL
  AND capacity_unit != 'KG_FREE'
  AND available_kg = 0
  AND status = 'ACTIVE';
