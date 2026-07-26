-- Retrait de la valeur déclarée du colis : remplacée côté produit par une
-- politique de remboursement informative (plafond configurable dony.reimbursement).
-- Le champ n'était utilisé que pour affichage + une validation ≤ 500 € ; aucune
-- logique métier (escrow, litige, remboursement) ne s'y appuyait. Le paiement
-- mobile money qui lisait ce champ comme montant est déjà retiré (cf. BidService).
-- IF EXISTS : rejoue sûr, aligné sur le style de V181.
ALTER TABLE bids DROP COLUMN IF EXISTS declared_value_eur;
ALTER TABLE package_requests DROP COLUMN IF EXISTS declared_value_eur;
ALTER TABLE disputes DROP COLUMN IF EXISTS declared_value_eur;
