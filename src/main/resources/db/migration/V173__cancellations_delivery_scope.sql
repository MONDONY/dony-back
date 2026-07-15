-- V173__cancellations_delivery_scope.sql
-- Un bid peut désormais avoir DEUX enregistrements cancellations distincts au
-- cours de sa vie : un HANDOVER (départ, existant) et un DELIVERY (arrivée,
-- nouveau) — ex. un no-show départ contesté puis résolu en faveur du voyageur
-- laisse le bid reprendre son cours jusqu'à l'arrivée, où un nouveau no-show
-- peut survenir. L'unicité passe donc de (bid_id) à (bid_id, scope).

ALTER TABLE cancellations ADD COLUMN scope VARCHAR(20) NOT NULL DEFAULT 'HANDOVER'
    CHECK (scope IN ('HANDOVER', 'DELIVERY'));

ALTER TABLE cancellations DROP CONSTRAINT uq_cancellations_bid_id;
ALTER TABLE cancellations ADD CONSTRAINT uq_cancellations_bid_id_scope UNIQUE (bid_id, scope);
