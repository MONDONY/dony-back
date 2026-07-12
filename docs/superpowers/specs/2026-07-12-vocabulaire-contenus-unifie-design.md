# Vocabulaire unifié des types de contenu — Design

## Contexte

Un voyageur crée la règle d'automatisation « SI type de contenu = Poissons → refuser ». Elle ne se déclenche jamais. Cause immédiate : « Poissons » n'existe dans aucune liste proposée à l'expéditeur. Cause profonde : **il n'existe pas de vocabulaire des types de contenu — il en existe neuf, divergents.**

### Les neuf listes

| # | Emplacement | Contenu |
|---|---|---|
| A | `dony_app/.../create_bid_bottom_sheet.dart:34` | Vêtements, Médicaments, Alim. sèche, Documents, Hi-fi, Téléphone, Autre |
| B | `dony_app/.../create_bid_screen.dart:25` | identique à A (copie littérale) |
| C | `dony_app/.../create_announcement/_create_announcement_constants.dart:8` | …, **Cosmétiques**, sans « Autre » |
| D | `dony_app/.../search_form_bottom_sheet.dart:18` | identique à C, redéfinie |
| E | `dony_app/.../trip_template_edit_screen.dart:21` | identique à C, redéfinie |
| F | `dony_app/.../corridor_alert_form_sheet.dart:17` | Documents, Vêtements, **Électronique**, **Nourriture**, Cosmétiques, Médicaments |
| G | `dony_app/.../package_request/data/models/content_category.dart:10` | enum typé, 9 valeurs, dont **Cadeaux** |
| H | `dony-pro/app/features/trajets/data/tripTemplates.ts:25` | Vêtements, Documents, Cosmétiques |
| I | `dony-back/src/main/resources/application.yml:122` | …, **Téléphones & hi-fi**, **Matériel informatique**, **Autres** |

Aucune n'est identique à une autre. Quatre d'entre elles (D, E, F, H) ne sont couvertes par aucun test : elles peuvent diverger davantage en silence.

### Deux encodages coexistent déjà en base

- `package_requests.content_category` : **code** en majuscules (`VETEMENTS`, `MEDICAMENTS`) — sérialisé depuis l'enum G via `wireName`.
- `bids.content_category` : **libellés d'affichage joints par virgule** (`"Vêtements, Poissons"`).
- `announcement_accepted_types.content_type` / `announcement_refused_types.content_type` : **libellés d'affichage**, texte libre.

`BidContentRules.assertNotRefused` et `CustomRuleConditionEvaluator` (moteur d'automatisation) matchent tous deux sur les **libellés**, insensibles à la casse. Une demande de colis stockée en `VETEMENTS` ne matche donc aucune règle portant sur « Vêtements ».

### L'endpoint de référence existe déjà, à moitié câblé

`GET /config/content-categories` (`ConfigController.java:26`) renvoie `List<String>` depuis `application.yml` (liste I). **Consommateur unique : dony-pro**, pour les chips « Ce que j'accepte » du formulaire d'annonce. `dony_app` ne l'appelle jamais — il duplique ses listes en dur.

## Objectif

Un vocabulaire unique, servi par le backend, consommé par tous les écrans des trois projets. Partout où une catégorie se choisit, l'utilisateur voit **la même liste**, et peut toujours **saisir un type libre** absent du catalogue.

## Le catalogue canonique

Dérivé de l'analyse de 2 610 annonces Monkolis sur le corridor Paris–Abidjan (361 mentionnant explicitement un contenu), validée par le porteur produit. Les catégories sont volontairement corridor-agnostiques.

| # | Libellé (valeur stockée) | Code (clé technique) | Emoji | Part observée |
|---|---|---|---|---|
| 1 | Documents & administratif | `DOCUMENTS` | 📄 | 36 % |
| 2 | Alimentation sèche | `ALIMENTATION_SECHE` | 🍚 | 18 % (avec le frais) |
| 3 | Produits frais / périssables | `PRODUITS_FRAIS` | 🐟 | — |
| 4 | Cosmétiques & parfums | `COSMETIQUES` | 💄 | 11 % |
| 5 | Vêtements & tissus | `VETEMENTS` | 👗 | 10 % |
| 6 | Chaussures | `CHAUSSURES` | 👟 | — |
| 7 | Médicaments traditionnels | `MEDICAMENTS_TRADITIONNELS` | 🌿 | 9 % |
| 8 | Téléphone & électronique | `ELECTRONIQUE` | 📱 | 3 % |
| 9 | Livres | `LIVRES` | 📚 | — |
| 10 | Cadeaux & jouets | `CADEAUX` | 🎁 | 5 % |
| 11 | Autre | `AUTRE` | 📦 | — |

Décisions dérivées des données :

- **Le sec et le périssable sont séparés** (2 et 3). Ils n'ont ni les mêmes contraintes douanières ni la même urgence, et le poisson fumé — le cas d'usage à l'origine de ce chantier — n'avait jusqu'ici aucune catégorie où atterrir.
- **« Sport & loisirs » est écarté** : l'analyse l'identifie comme un artefact (noms d'entreprises « … Sports »), volume réel estimé < 5 annonces.
- **« Argent & valeurs » est écarté** : ce n'est pas une catégorie sélectionnable mais un **interdit** (LCB-FT, source principale d'arnaques dans le corpus). Traité dans un chantier séparé, avec les médicaments sur ordonnance.

### Invariant : aucun libellé ne contient de virgule

`bids.content_category` encode plusieurs catégories **jointes par virgule**. Un libellé contenant une virgule casserait le `split(",")` de `BidContentRules` et du moteur d'automatisation. Les 11 libellés respectent cet invariant ; un test le verrouille, côté backend, sur le catalogue servi.

## Architecture

### Source de vérité : le backend

Le catalogue vit dans `application.yml` sous `dony.content-categories`, désormais structuré :

```yaml
dony:
  content-categories:
    - code: DOCUMENTS
      label: "Documents & administratif"
      emoji: "📄"
    - code: ALIMENTATION_SECHE
      label: "Alimentation sèche"
      emoji: "🍚"
    # … 11 entrées
```

`GET /config/content-categories` renvoie désormais `List<ContentCategoryResponse>` (`{code, label, emoji}`) au lieu de `List<String>`.

**C'est un changement de contrat cassant.** Le consommateur unique actuel (dony-pro, `configService.fetchContentCategories()`) est mis à jour dans le même chantier. L'endpoint reste public (pas d'authentification requise) — il ne sert que des données de référence.

### Valeur stockée : le libellé, pas le code

Le `code` est une **clé technique** (lookup d'icône Flutter, i18n future, robustesse au renommage). Il n'est **jamais persisté**.

La valeur persistée en base reste le **libellé** (`"Documents & administratif"`), comme aujourd'hui pour les bids et les annonces. Conséquences :

- `BidContentRules` et `CustomRuleConditionEvaluator` fonctionnent **sans aucune modification** — ils matchent déjà sur les libellés, insensibles à la casse et aux espaces.
- Un type saisi librement (« Poissons ») est stocké tel quel, aux côtés des libellés canoniques : le matching ne fait pas la différence, la saisie libre continue de fonctionner partout.
- Renommer un libellé plus tard imposera une migration de données. C'est déjà le cas aujourd'hui ; on ne dégrade rien.

### Migration des données existantes

Sans migration, un voyageur ayant accepté « Hi-fi » hier ne matcherait plus un colis « Téléphone & électronique » aujourd'hui. Migration `V171__unify_content_categories.sql` :

1. `package_requests.content_category` : **codes → libellés** (`VETEMENTS` → `Vêtements & tissus`, `ALIMENTATION` → `Alimentation sèche`, `HIFI` → `Téléphone & électronique`, `TELEPHONE` → `Téléphone & électronique`, `COSMETIQUES` → `Cosmétiques & parfums`, `MEDICAMENTS` → `Médicaments traditionnels`, `DOCUMENTS` → `Documents & administratif`, `CADEAUX` → `Cadeaux & jouets`, `AUTRE` → `Autre`).
2. `announcement_accepted_types.content_type` et `announcement_refused_types.content_type` : **anciens libellés → nouveaux** (`Alim. sèche` → `Alimentation sèche`, `Hi-fi` → `Téléphone & électronique`, `Téléphone` → `Téléphone & électronique`, `Téléphones & hi-fi` → idem, `Cosmétiques` → `Cosmétiques & parfums`, `Vêtements` → `Vêtements & tissus`, `Médicaments` → `Médicaments traditionnels`, `Documents` → `Documents & administratif`, `Cadeaux` → `Cadeaux & jouets`, `Matériel informatique` → `Téléphone & électronique`, `Électronique` → `Téléphone & électronique`, `Nourriture` → `Alimentation sèche`, `Autres` → `Autre`).
3. `bids.content_category` : même mapping, appliqué **sur chaque item de la chaîne jointe par virgule**. Réalisé en SQL par `REPLACE` successifs sur la chaîne complète, en traitant les libellés du plus long au plus court pour éviter qu'un remplacement partiel n'en corrompe un autre (`Téléphones & hi-fi` avant `Téléphone`).
4. Les valeurs libres non reconnues (« Poissons », « Liquides ») sont **laissées intactes** — c'est le comportement voulu.
5. Après migration, `announcement_accepted_types` peut contenir des doublons (`Hi-fi` et `Téléphone` convergent tous deux vers `Téléphone & électronique`). Un `DELETE` de déduplication conclut la migration.

Cette migration touche des données de production. Elle est idempotente (rejouable sans dommage) et ne supprime aucune information autre que les doublons qu'elle crée elle-même.

### Un composant de sélection, deux comportements

Partout où l'on choisit un ou plusieurs types de contenu, l'UI présente **le catalogue complet** et **permet la saisie libre**. Deux familles d'écrans :

- **Sélection multiple** (ce que le voyageur accepte / refuse, contenu d'un colis, filtres) : liste déroulante à cocher + champ « Ajouter un autre type… ». C'est le remplacement demandé des chips actuelles.
- **Sélection unique** (valeur d'une condition de règle d'automatisation dans dony-pro) : liste déroulante simple + option « Autre valeur… » ouvrant un champ texte.

### Écrans à câbler

**dony_app (Flutter)** — les listes en dur A–G disparaissent, remplacées par un `ContentCategoryRepository` (appel `/config/content-categories`, cache mémoire + fallback embarqué si le réseau échoue, pour ne jamais bloquer un formulaire) :

| Écran | Liste supprimée | Remplacement |
|---|---|---|
| Création de bid (bottom sheet + écran plein) | A, B | sélection multiple |
| Publication de trajet — « Ce que j'accepte » | C | sélection multiple (déjà dotée d'une saisie libre, conservée) |
| Publication de trajet — « Ce que je refuse » | — | sélection multiple (aujourd'hui saisie libre pure) |
| Filtre de recherche de trajets | D | sélection multiple |
| Édition de modèle de trajet | E | sélection multiple |
| Alerte corridor | F | sélection multiple |
| Wizard demande de colis | G (enum) | sélection multiple ; l'enum est supprimé, les icônes passent par un `iconForCode(code)` local avec repli générique |

**dony-pro (Nuxt)** :

| Écran | Changement |
|---|---|
| `NewAnnouncementForm.vue` / `ContentTagChips.vue` | consomme la nouvelle forme `{code,label,emoji}` de l'endpoint |
| `tripTemplates.ts` (liste H) | `DEFAULT_CATEGORIES` alignée sur les libellés canoniques |
| `AutomationRuleModal.vue` | le champ `value` devient une **liste déroulante** quand `field = content_type` (aujourd'hui : `<input type="text">` pour tous les champs) — c'est ce qui rend impossible la création d'une règle qui ne matchera jamais |

**dony-back** : catalogue structuré, endpoint, migration. `BidContentRules` et le moteur d'automatisation restent inchangés.

## Ce que ça corrige

Le bug d'origine est réglé de deux façons, indépendantes :

1. « Poissons » relève désormais de **Produits frais / périssables**, une catégorie que l'expéditeur peut cocher et que le voyageur peut refuser.
2. Le voyageur qui tape malgré tout « Poissons » dans dony-pro se voit proposer une liste déroulante : une règle portant sur une valeur inexistante devient **impossible à créer**.

## Tests

- **Backend** : le catalogue servi contient 11 entrées ; aucun libellé ne contient de virgule (invariant du `split`) ; codes uniques ; l'endpoint renvoie bien `{code,label,emoji}`. Migration : test d'intégration Flyway vérifiant le mapping des trois tables, le traitement item par item de la chaîne jointe par virgule, la préservation des valeurs libres, et l'idempotence.
- **Flutter** : le repository met en cache, retombe sur le catalogue embarqué en cas d'échec réseau, et n'empêche jamais l'affichage d'un formulaire. Chaque écran câblé affiche le catalogue et accepte une saisie libre. Le test `create_bid_screen_test.dart:216` (qui recopie la liste A en dur) est réécrit pour itérer sur la source.
- **dony-pro** : `AutomationRuleModal` affiche une liste déroulante pour `content_type` et un champ texte pour les autres champs ; `ContentTagChips` consomme la nouvelle forme.

## Hors scope (chantier séparé)

- **Contenus interdits** : argent liquide, change de devises, médicaments sur ordonnance. Nécessite une liste de blocage, un refus à la création du colis côté backend, des CGU dédiées et des avertissements douaniers (> 430 €, ANSM). Sujet juridique à part entière.
- Tarification par catégorie (forfait documents), feature « Shopper », avertissements douaniers contextuels — pistes produit issues de la même analyse, non retenues ici.
