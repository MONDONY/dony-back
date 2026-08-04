-- Index manquants sur users pour les filtres de recherche du marketplace.
--
-- AnnouncementSpecification.kycVerifiedOnly() / kiloProOnly() / minRating()
-- exécutent chacune une sous-requête "SELECT id FROM users WHERE ..." à
-- chaque recherche publique de trajet filtrée en conséquence. Ces trois
-- colonnes n'avaient jusqu'ici aucun index dédié : un scan complet de la
-- table users à chaque recherche "voyageurs vérifiés KYC", "Kilo Pro
-- uniquement" ou "note minimum".

-- kilo_pro : booléen très déséquilibré (grande majorité à FALSE) → index
-- partiel sur les TRUE uniquement, même pattern que idx_users_publishing_suspended.
CREATE INDEX IF NOT EXISTS idx_users_kilo_pro ON users (kilo_pro) WHERE kilo_pro = TRUE;

-- kyc_status : filtré par égalité exacte ('VERIFIED') dans kycVerifiedOnly().
CREATE INDEX IF NOT EXISTS idx_users_kyc_status ON users (kyc_status);

-- average_rating : comparaison >= dans minRating(). Index partiel : tant
-- qu'un utilisateur n'a pas reçu d'évaluation, la colonne est NULL et ne
-- doit jamais matcher un filtre "note minimum" — inutile de l'indexer.
CREATE INDEX IF NOT EXISTS idx_users_average_rating ON users (average_rating) WHERE average_rating IS NOT NULL;
