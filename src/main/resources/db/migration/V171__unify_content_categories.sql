-- V171 — Vocabulaire unifié des types de contenu.
--
-- QUATRE formes coexistent en production (pas trois — cf. V143) :
--   package_requests.content_category      : soit un code enum majuscule hérité de
--                                             l'ancien enum Flutter ('VETEMENTS'),
--                                             soit — depuis V143 — une chaîne de
--                                             libellés joints par virgule
--                                             ('Vêtements,Médicaments,Fragile').
--                                             Les deux formats coexistent en base.
--   bids.content_category                  : libellés joints par virgule ('Vêtements, Poissons') —
--                                             recopiés depuis package_requests au moment du bid,
--                                             donc peut hériter d'un vieux code enum isolé
--                                             pour les bids les plus anciens.
--   announcement_{accepted,refused}_types  : libellés, une ligne par item (texte libre saisi
--                                             par le voyageur — jamais de code enum).
--
-- On converge sur le LIBELLÉ canonique partout. Les valeurs libres non reconnues
-- ('Poissons', 'Liquides') sont laissées intactes — c'est voulu, casse comprise.
--
-- Idempotente par construction : chaque transformation est un CASE d'égalité exacte
-- (jamais un REPLACE substring). Un libellé déjà canonique retombe systématiquement
-- dans la branche ELSE et n'est jamais re-transformé, quel que soit le nombre de fois
-- où cette migration est rejouée.
--
-- Insensible à la casse : le CASE compare sur lower(trim(...)) — le runtime
-- (BidContentRules.assertNotRefused) compare lui aussi en lower/strip, donc une
-- valeur saisie 'hi-fi' ou 'téléphone' doit être reconnue au même titre que 'Hi-fi'
-- ou 'Téléphone'. La valeur ÉCRITE reste le libellé canonique correctement
-- capitalisé ; le ELSE renvoie la valeur d'origine telle quelle (pas sa version en
-- minuscules) pour ne pas détruire la casse des valeurs libres comme « Poissons ».

-- ─── 0. Élargissement défensif avant normalisation ────────────────────────────
-- V143 plafonne bids.content_category et package_requests.content_category à
-- VARCHAR(255). La normalisation ALLONGE les chaînes ('Hi-fi' = 5 caractères →
-- 'Téléphone & électronique' = 24) : un colis multi-catégories chargé peut
-- dépasser la limite pendant l'UPDATE et faire échouer (et donc annuler) toute la
-- migration en production. On élargit en TEXT avant toute écriture — le DTO
-- backend garde sa validation @Size(max=255) sur les nouvelles saisies.
ALTER TABLE bids ALTER COLUMN content_category TYPE TEXT;
ALTER TABLE package_requests ALTER COLUMN content_category TYPE TEXT;

-- ─── 1. bids / package_requests : chaîne jointe par virgule ──────────────────
-- Colonne unique contenant un ou plusieurs items séparés par ', '. Plutôt que des
-- REPLACE en cascade (sensibles à l'ordre et non idempotents — un
-- REPLACE('Téléphone', 'Téléphone & électronique') rejoué sur un résultat déjà
-- migré produirait 'Téléphone & électronique & électronique'), on décompose la
-- chaîne en items, on applique un CASE d'égalité exacte item par item, on
-- déduplique en préservant l'ordre de première occurrence (un bid 'Hi-fi, Téléphone'
-- ne doit pas devenir 'Téléphone & électronique, Téléphone & électronique'), puis on
-- recompose.
--
-- Le CASE couvre à la fois les 9 codes enum majuscules ET les 14 libellés legacy :
-- package_requests peut contenir l'un ou l'autre format (voire un code enum isolé
-- sans virgule, traité alors comme un item unique — gratuit avec cette approche).
-- bids peut hériter d'un vieux code enum pour ses lignes les plus anciennes. Les
-- deux ensembles de clés sont disjoints (aucun code enum n'égale un libellé une
-- fois mis en minuscule), donc un seul CASE fusionné reste idempotent. Les deux
-- colonnes ci-dessous partagent volontairement le texte EXACT de ce CASE — copie
-- intentionnelle, à faire évoluer ensemble si de nouvelles clés apparaissent.
UPDATE bids SET content_category = (
    SELECT string_agg(lbl, ', ' ORDER BY ord)
    FROM (
        SELECT
            CASE lower(trim(item))
                WHEN 'vetements'              THEN 'Vêtements & tissus'
                WHEN 'medicaments'             THEN 'Médicaments traditionnels'
                WHEN 'alimentation'            THEN 'Alimentation sèche'
                WHEN 'hifi'                    THEN 'Téléphone & électronique'
                WHEN 'documents'               THEN 'Documents & administratif'
                WHEN 'telephone'               THEN 'Téléphone & électronique'
                WHEN 'cosmetiques'              THEN 'Cosmétiques & parfums'
                WHEN 'cadeaux'                  THEN 'Cadeaux & jouets'
                WHEN 'autre'                    THEN 'Autre'
                WHEN 'téléphones & hi-fi'      THEN 'Téléphone & électronique'
                WHEN 'matériel informatique'   THEN 'Téléphone & électronique'
                WHEN 'électronique'             THEN 'Téléphone & électronique'
                WHEN 'hi-fi'                    THEN 'Téléphone & électronique'
                WHEN 'téléphone'                THEN 'Téléphone & électronique'
                WHEN 'alim. sèche'              THEN 'Alimentation sèche'
                WHEN 'nourriture'               THEN 'Alimentation sèche'
                WHEN 'cosmétiques'              THEN 'Cosmétiques & parfums'
                WHEN 'cosmét.'                  THEN 'Cosmétiques & parfums'
                WHEN 'vêtements'                THEN 'Vêtements & tissus'
                WHEN 'médicaments'              THEN 'Médicaments traditionnels'
                WHEN 'autres'                   THEN 'Autre'
                ELSE trim(item)
            END AS lbl,
            min(ord) AS ord
        FROM unnest(string_to_array(bids.content_category, ',')) WITH ORDINALITY AS t(item, ord)
        GROUP BY 1
    ) d
)
WHERE content_category IS NOT NULL AND content_category <> '';

-- Voir bloc 1 ci-dessus : CASE identique (fusion codes + libellés), même logique de
-- déduplication/recomposition. package_requests peut contenir un code enum isolé
-- ('VETEMENTS'), une liste de libellés ('Vêtements,Médicaments'), ou déjà un mélange
-- migré partiellement — les trois cas sont couverts par la même décomposition.
UPDATE package_requests SET content_category = (
    SELECT string_agg(lbl, ', ' ORDER BY ord)
    FROM (
        SELECT
            CASE lower(trim(item))
                WHEN 'vetements'              THEN 'Vêtements & tissus'
                WHEN 'medicaments'             THEN 'Médicaments traditionnels'
                WHEN 'alimentation'            THEN 'Alimentation sèche'
                WHEN 'hifi'                    THEN 'Téléphone & électronique'
                WHEN 'documents'               THEN 'Documents & administratif'
                WHEN 'telephone'               THEN 'Téléphone & électronique'
                WHEN 'cosmetiques'              THEN 'Cosmétiques & parfums'
                WHEN 'cadeaux'                  THEN 'Cadeaux & jouets'
                WHEN 'autre'                    THEN 'Autre'
                WHEN 'téléphones & hi-fi'      THEN 'Téléphone & électronique'
                WHEN 'matériel informatique'   THEN 'Téléphone & électronique'
                WHEN 'électronique'             THEN 'Téléphone & électronique'
                WHEN 'hi-fi'                    THEN 'Téléphone & électronique'
                WHEN 'téléphone'                THEN 'Téléphone & électronique'
                WHEN 'alim. sèche'              THEN 'Alimentation sèche'
                WHEN 'nourriture'               THEN 'Alimentation sèche'
                WHEN 'cosmétiques'              THEN 'Cosmétiques & parfums'
                WHEN 'cosmét.'                  THEN 'Cosmétiques & parfums'
                WHEN 'vêtements'                THEN 'Vêtements & tissus'
                WHEN 'médicaments'              THEN 'Médicaments traditionnels'
                WHEN 'autres'                   THEN 'Autre'
                ELSE trim(item)
            END AS lbl,
            min(ord) AS ord
        FROM unnest(string_to_array(package_requests.content_category, ',')) WITH ORDINALITY AS t(item, ord)
        GROUP BY 1
    ) d
)
WHERE content_category IS NOT NULL AND content_category <> '';

-- ─── 2. announcement_accepted_types / refused_types : libellé → libellé ──────
-- Une ligne par item, toujours du texte libre saisi par le voyageur (jamais de
-- code enum ici) : un CASE d'égalité exacte sur lower(trim(...)) suffit, pas de
-- décomposition. Le WHERE restreint l'UPDATE aux lignes qui changent réellement
-- (limite le bloat et les verrous sur des tables sans PK).
UPDATE announcement_accepted_types SET content_type = CASE lower(trim(content_type))
    WHEN 'téléphones & hi-fi'      THEN 'Téléphone & électronique'
    WHEN 'matériel informatique'   THEN 'Téléphone & électronique'
    WHEN 'électronique'             THEN 'Téléphone & électronique'
    WHEN 'hi-fi'                    THEN 'Téléphone & électronique'
    WHEN 'téléphone'                THEN 'Téléphone & électronique'
    WHEN 'alim. sèche'              THEN 'Alimentation sèche'
    WHEN 'nourriture'               THEN 'Alimentation sèche'
    WHEN 'cosmétiques'              THEN 'Cosmétiques & parfums'
    WHEN 'cosmét.'                  THEN 'Cosmétiques & parfums'
    WHEN 'vêtements'                THEN 'Vêtements & tissus'
    WHEN 'médicaments'              THEN 'Médicaments traditionnels'
    WHEN 'documents'                THEN 'Documents & administratif'
    WHEN 'cadeaux'                  THEN 'Cadeaux & jouets'
    WHEN 'autres'                   THEN 'Autre'
    ELSE content_type
END
WHERE lower(trim(content_type)) IN (
    'téléphones & hi-fi', 'matériel informatique', 'électronique', 'hi-fi', 'téléphone',
    'alim. sèche', 'nourriture', 'cosmétiques', 'cosmét.', 'vêtements', 'médicaments',
    'documents', 'cadeaux', 'autres'
);

UPDATE announcement_refused_types SET content_type = CASE lower(trim(content_type))
    WHEN 'téléphones & hi-fi'      THEN 'Téléphone & électronique'
    WHEN 'matériel informatique'   THEN 'Téléphone & électronique'
    WHEN 'électronique'             THEN 'Téléphone & électronique'
    WHEN 'hi-fi'                    THEN 'Téléphone & électronique'
    WHEN 'téléphone'                THEN 'Téléphone & électronique'
    WHEN 'alim. sèche'              THEN 'Alimentation sèche'
    WHEN 'nourriture'               THEN 'Alimentation sèche'
    WHEN 'cosmétiques'              THEN 'Cosmétiques & parfums'
    WHEN 'cosmét.'                  THEN 'Cosmétiques & parfums'
    WHEN 'vêtements'                THEN 'Vêtements & tissus'
    WHEN 'médicaments'              THEN 'Médicaments traditionnels'
    WHEN 'documents'                THEN 'Documents & administratif'
    WHEN 'cadeaux'                  THEN 'Cadeaux & jouets'
    WHEN 'autres'                   THEN 'Autre'
    ELSE content_type
END
WHERE lower(trim(content_type)) IN (
    'téléphones & hi-fi', 'matériel informatique', 'électronique', 'hi-fi', 'téléphone',
    'alim. sèche', 'nourriture', 'cosmétiques', 'cosmét.', 'vêtements', 'médicaments',
    'documents', 'cadeaux', 'autres'
);

-- ─── 3. Déduplication intra-table : plusieurs libellés legacy convergent vers le
--        même libellé canonique (ex. 'Hi-fi' et 'Téléphone' → 'Téléphone & électronique').
--        Ces tables n'ont ni PK ni colonne d'ordre : les lignes d'un même groupe
--        (announcement_id, content_type) sont interchangeables une fois toutes
--        réécrites par l'UPDATE ci-dessus — on en garde une arbitrairement (ctid
--        n'est qu'une position physique, pas un âge). Idempotente : rejouée sans
--        nouveaux doublons, elle ne supprime plus rien.
DELETE FROM announcement_accepted_types a
WHERE a.ctid <> (SELECT MIN(b.ctid) FROM announcement_accepted_types b
                 WHERE b.announcement_id = a.announcement_id
                   AND b.content_type = a.content_type);

DELETE FROM announcement_refused_types a
WHERE a.ctid <> (SELECT MIN(b.ctid) FROM announcement_refused_types b
                 WHERE b.announcement_id = a.announcement_id
                   AND b.content_type = a.content_type);

-- ─── 4. Collision accepted/refused : la normalisation peut fusionner deux
--        libellés legacy DISTINCTS — l'un accepté, l'autre refusé — vers le même
--        libellé canonique pour la MÊME annonce (ex. voyageur accepte 'Alim. sèche'
--        et refuse 'Nourriture' ; les deux valent désormais 'Alimentation sèche').
--        BidContentRules.assertNotRefused ne consulte que la liste des refus : sans
--        ce correctif, le refus l'emporterait et bloquerait en 422 un colis que le
--        voyageur avait explicitement accepté.
--
--        Règle produit tranchée : on supprime la ligne de refus et on garde
--        l'acceptation. Le voyageur n'a jamais exprimé de contradiction — c'est
--        notre normalisation qui fusionne deux libellés qu'il pensait distincts.
--        Garder le refus bloquerait silencieusement des colis en 422 sans qu'il
--        comprenne pourquoi (non recouvrable de son point de vue). Garder
--        l'acceptation lui laisse au contraire la main : il peut refuser
--        manuellement le bid concerné, ou ré-ajouter un refus explicite s'il le
--        souhaite. On échoue du côté récupérable.
--
--        Idempotente : rejouée, elle ne trouve plus de collision (le refus
--        correspondant a déjà été supprimé) et ne supprime plus rien.
DELETE FROM announcement_refused_types r
WHERE EXISTS (
    SELECT 1 FROM announcement_accepted_types a
    WHERE a.announcement_id = r.announcement_id
      AND a.content_type = r.content_type
);
