-- V171 — Vocabulaire unifié des types de contenu.
--
-- Trois formes coexistaient :
--   package_requests.content_category      : code enum majuscule ('VETEMENTS')
--   bids.content_category                  : libellés joints par virgule ('Vêtements, Poissons')
--   announcement_{accepted,refused}_types  : libellés, une ligne par item
--
-- On converge sur le LIBELLÉ canonique partout. Les valeurs libres non reconnues
-- ('Poissons', 'Liquides') sont laissées intactes — c'est voulu.
--
-- Idempotente par construction : chaque transformation est un CASE d'égalité exacte
-- (jamais un REPLACE substring). Un libellé déjà canonique retombe systématiquement
-- dans la branche ELSE et n'est jamais re-transformé, quel que soit le nombre de fois
-- où cette migration est rejouée.

-- ─── 1. package_requests : code enum → libellé ───────────────────────────────
UPDATE package_requests SET content_category = CASE content_category
    WHEN 'VETEMENTS'    THEN 'Vêtements & tissus'
    WHEN 'MEDICAMENTS'  THEN 'Médicaments traditionnels'
    WHEN 'ALIMENTATION' THEN 'Alimentation sèche'
    WHEN 'HIFI'         THEN 'Téléphone & électronique'
    WHEN 'DOCUMENTS'    THEN 'Documents & administratif'
    WHEN 'TELEPHONE'    THEN 'Téléphone & électronique'
    WHEN 'COSMETIQUES'  THEN 'Cosmétiques & parfums'
    WHEN 'CADEAUX'      THEN 'Cadeaux & jouets'
    WHEN 'AUTRE'        THEN 'Autre'
    ELSE content_category
END
WHERE content_category IN ('VETEMENTS','MEDICAMENTS','ALIMENTATION','HIFI','DOCUMENTS',
                           'TELEPHONE','COSMETIQUES','CADEAUX','AUTRE');

-- ─── 2. announcement_accepted_types / refused_types : libellé → libellé ──────
-- Une ligne par item : un CASE d'égalité exacte suffit, pas de REPLACE.
UPDATE announcement_accepted_types SET content_type = CASE content_type
    WHEN 'Téléphones & hi-fi'    THEN 'Téléphone & électronique'
    WHEN 'Matériel informatique' THEN 'Téléphone & électronique'
    WHEN 'Électronique'          THEN 'Téléphone & électronique'
    WHEN 'Hi-fi'                 THEN 'Téléphone & électronique'
    WHEN 'Téléphone'             THEN 'Téléphone & électronique'
    WHEN 'Alim. sèche'           THEN 'Alimentation sèche'
    WHEN 'Nourriture'            THEN 'Alimentation sèche'
    WHEN 'Cosmétiques'           THEN 'Cosmétiques & parfums'
    WHEN 'Cosmét.'               THEN 'Cosmétiques & parfums'
    WHEN 'Vêtements'             THEN 'Vêtements & tissus'
    WHEN 'Médicaments'           THEN 'Médicaments traditionnels'
    WHEN 'Documents'             THEN 'Documents & administratif'
    WHEN 'Cadeaux'               THEN 'Cadeaux & jouets'
    WHEN 'Autres'                THEN 'Autre'
    ELSE content_type
END;

UPDATE announcement_refused_types SET content_type = CASE content_type
    WHEN 'Téléphones & hi-fi'    THEN 'Téléphone & électronique'
    WHEN 'Matériel informatique' THEN 'Téléphone & électronique'
    WHEN 'Électronique'          THEN 'Téléphone & électronique'
    WHEN 'Hi-fi'                 THEN 'Téléphone & électronique'
    WHEN 'Téléphone'             THEN 'Téléphone & électronique'
    WHEN 'Alim. sèche'           THEN 'Alimentation sèche'
    WHEN 'Nourriture'            THEN 'Alimentation sèche'
    WHEN 'Cosmétiques'           THEN 'Cosmétiques & parfums'
    WHEN 'Cosmét.'               THEN 'Cosmétiques & parfums'
    WHEN 'Vêtements'             THEN 'Vêtements & tissus'
    WHEN 'Médicaments'           THEN 'Médicaments traditionnels'
    WHEN 'Documents'             THEN 'Documents & administratif'
    WHEN 'Cadeaux'               THEN 'Cadeaux & jouets'
    WHEN 'Autres'                THEN 'Autre'
    ELSE content_type
END;

-- ─── 3. Déduplication : plusieurs libellés legacy convergent vers le même
--        libellé canonique (ex. 'Hi-fi' et 'Téléphone' → 'Téléphone & électronique').
--        On ne garde que la ligne la plus ancienne (ctid minimal) par
--        (announcement_id, content_type). Idempotente : rejouée sans nouveaux
--        doublons, elle ne supprime plus rien.
DELETE FROM announcement_accepted_types a
WHERE a.ctid <> (SELECT MIN(b.ctid) FROM announcement_accepted_types b
                 WHERE b.announcement_id = a.announcement_id
                   AND b.content_type = a.content_type);

DELETE FROM announcement_refused_types a
WHERE a.ctid <> (SELECT MIN(b.ctid) FROM announcement_refused_types b
                 WHERE b.announcement_id = a.announcement_id
                   AND b.content_type = a.content_type);

-- ─── 4. bids.content_category : chaîne jointe par virgule ────────────────────
-- Colonne unique contenant plusieurs libellés séparés par ', '. Plutôt que des
-- REPLACE en cascade (sensibles à l'ordre et non idempotents — cf. brief : un
-- REPLACE('Téléphone', 'Téléphone & électronique') rejoué sur un résultat déjà
-- migré produirait 'Téléphone & électronique & électronique'), on décompose la
-- chaîne en items, on applique un CASE d'égalité exacte item par item (comme les
-- blocs 2 et 3, donc idempotent par construction et insensible à l'ordre), puis
-- on recompose en préservant l'ordre d'origine.
UPDATE bids SET content_category = (
    SELECT string_agg(
        CASE trim(item)
            WHEN 'Téléphones & hi-fi'    THEN 'Téléphone & électronique'
            WHEN 'Matériel informatique' THEN 'Téléphone & électronique'
            WHEN 'Électronique'          THEN 'Téléphone & électronique'
            WHEN 'Hi-fi'                 THEN 'Téléphone & électronique'
            WHEN 'Téléphone'             THEN 'Téléphone & électronique'
            WHEN 'Alim. sèche'           THEN 'Alimentation sèche'
            WHEN 'Nourriture'            THEN 'Alimentation sèche'
            WHEN 'Cosmétiques'           THEN 'Cosmétiques & parfums'
            WHEN 'Cosmét.'               THEN 'Cosmétiques & parfums'
            WHEN 'Vêtements'             THEN 'Vêtements & tissus'
            WHEN 'Médicaments'           THEN 'Médicaments traditionnels'
            WHEN 'Documents'             THEN 'Documents & administratif'
            WHEN 'Cadeaux'               THEN 'Cadeaux & jouets'
            WHEN 'Autres'                THEN 'Autre'
            ELSE trim(item)
        END, ', ' ORDER BY ord)
    FROM unnest(string_to_array(bids.content_category, ',')) WITH ORDINALITY AS t(item, ord)
)
WHERE content_category IS NOT NULL AND content_category <> '';
