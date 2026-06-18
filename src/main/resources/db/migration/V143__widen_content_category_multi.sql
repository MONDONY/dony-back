-- Catégories de colis multiples : stockées en une chaîne de libellés jointe par
-- virgule (ex. « Vêtements,Médicaments,Fragile »). La colonne unique doit donc
-- accueillir plusieurs valeurs. On élargit aussi bids.content_category car le
-- bid matérialisé recopie la catégorie de la demande (BidContentRules splitte
-- déjà sur la virgule).
ALTER TABLE package_requests ALTER COLUMN content_category TYPE VARCHAR(255);
ALTER TABLE bids ALTER COLUMN content_category TYPE VARCHAR(255);
