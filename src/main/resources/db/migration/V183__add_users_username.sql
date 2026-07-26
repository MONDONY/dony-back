-- Identifiant public généré à la création du compte : « user » + horodatage.
--
-- Avant cette colonne, un compte naissait sans nom (AuthService.createUser n'écrit
-- ni first_name ni last_name) et chaque couche inventait son propre repli : le
-- client affichait le numéro de téléphone ou l'email, le serveur retournait
-- « Expéditeur », « Voyageur », « Utilisateur » ou NULL selon le service appelé.
-- Un même compte portait donc plusieurs noms selon l'écran, et son numéro était
-- exposé comme nom public — ce que le réglage « Masquer mon numéro » interdit.
--
-- Le username devient le repli unique : il ne s'affiche que si l'utilisateur n'a
-- pas renseigné de prénom, auquel cas ce dernier reste prioritaire.

ALTER TABLE users ADD COLUMN IF NOT EXISTS username VARCHAR(32);

-- Rattrapage des comptes existants, à partir de leur date de création pour que la
-- valeur reste cohérente avec celle qu'aurait produite le générateur applicatif.
-- ROW_NUMBER départage les comptes créés dans la même seconde (jeux de données de
-- test insérés en lot), sans quoi l'index unique ci-dessous échouerait.
WITH numbered AS (
    SELECT id,
           'user' || EXTRACT(EPOCH FROM created_at)::BIGINT AS base,
           ROW_NUMBER() OVER (
               PARTITION BY EXTRACT(EPOCH FROM created_at)::BIGINT
               ORDER BY id
           ) AS rn
    FROM users
    WHERE username IS NULL
)
UPDATE users u
SET username = CASE WHEN n.rn = 1 THEN n.base ELSE n.base || n.rn END
FROM numbered n
WHERE u.id = n.id;

ALTER TABLE users ALTER COLUMN username SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_users_username ON users (username);
