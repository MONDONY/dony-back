# Vocabulaire unifié des types de contenu — Design

## Contexte

Un voyageur crée la règle d'automatisation « SI type de contenu = Poissons → refuser ». Elle ne se déclenche jamais. Cause immédiate : « Poissons » n'existe dans aucune liste proposée à l'expéditeur. Cause profonde : **il n'existe pas de vocabulaire des types de contenu — il en existe neuf, divergents.**

### Les neuf listes

| # | Emplacement | Contenu |
|---|---|---|
| A | `yadony_app/.../create_bid_bottom_sheet.dart:34` | Vêtements, Médicaments, Alim. sèche, Documents, Hi-fi, Téléphone, Autre |
| B | `yadony_app/.../create_bid_screen.dart:25` | identique à A (copie littérale) |
| C | `yadony_app/.../create_announcement/_create_announcement_constants.dart:8` | …, **Cosmétiques**, sans « Autre » |
| D | `yadony_app/.../search_form_bottom_sheet.dart:18` | identique à C, redéfinie |
| E | `yadony_app/.../trip_template_edit_screen.dart:21` | identique à C, redéfinie |
| F | `yadony_app/.../corridor_alert_form_sheet.dart:17` | Documents, Vêtements, **Électronique**, **Nourriture**, Cosmétiques, Médicaments |
| G | `yadony_app/.../package_request/data/models/content_category.dart:10` | enum typé, 9 valeurs, dont **Cadeaux** |
| H | `yadony-pro/app/features/trajets/data/tripTemplates.ts:25` | Vêtements, Documents, Cosmétiques |
| I | `yadony-back/src/main/resources/application.yml:122` | …, **Téléphones & hi-fi**, **Matériel informatique**, **Autres** |

Aucune n'est identique à une autre. Quatre d'entre elles (D, E, F, H) ne sont couvertes par aucun test : elles peuvent diverger davantage en silence.

### Deux encodages coexistent déjà en base

- `package_requests.content_category` : **code** en majuscules (`VETEMENTS`, `MEDICAMENTS`) — sérialisé depuis l'enum G via `wireName`.
- `bids.content_category` : **libellés d'affichage joints par virgule** (`"Vêtements, Poissons"`).
- `announcement_accepted_types.content_type` / `announcement_refused_types.content_type` : **libellés d'affichage**, texte libre.

`BidContentRules.assertNotRefused` et `CustomRuleConditionEvaluator` (moteur d'automatisation) matchent tous deux sur les **libellés**, insensibles à la casse. Une demande de colis stockée en `VETEMENTS` ne matche donc aucune règle portant sur « Vêtements ».

### L'endpoint de référence existe déjà, à moitié câblé

`GET /config/content-categories` (`ConfigController.java:26`) renvoie `List<String>` depuis `application.yml` (liste I). **Consommateur unique : yadony-pro**, pour les chips « Ce que j'accepte » du formulaire d'annonce. `yadony_app` ne l'appelle jamais — il duplique ses listes en dur.

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

Le catalogue vit dans `application.yml` sous `yadony.content-categories`, désormais structuré :

```yaml
yadony:
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

**C'est un changement de contrat cassant.** Le consommateur unique actuel (yadony-pro, `configService.fetchContentCategories()`) est mis à jour dans le même chantier. L'endpoint reste public (pas d'authentification requise) — il ne sert que des données de référence.

### Valeur stockée : le libellé, pas le code

Le `code` est une **clé technique** (lookup d'icône Flutter, i18n future, robustesse au renommage). Il n'est **jamais persisté**.

La valeur persistée en base reste le **libellé** (`"Documents & administratif"`), comme aujourd'hui pour les bids et les annonces. Conséquences :

- `BidContentRules` et `CustomRuleConditionEvaluator` fonctionnent **sans aucune modification** — ils matchent déjà sur les libellés, insensibles à la casse et aux espaces.
- Un type saisi librement (« Poissons ») est stocké tel quel, aux côtés des libellés canoniques : le matching ne fait pas la différence, la saisie libre continue de fonctionner partout.
- Renommer un libellé plus tard imposera une migration de données. C'est déjà le cas aujourd'hui ; on ne dégrade rien.

### Migration des données existantes

Sans migration, un voyageur ayant accepté « Hi-fi » hier ne matcherait plus un colis « Téléphone & électronique » aujourd'hui. Migration `V171__unify_content_categories.sql`, appliquée à **six emplacements** (une revue finale en a débusqué deux de plus que la version initiale de ce document, cf. tableau ci-dessous) :

| # | Emplacement | Forme | Traitement |
|---|---|---|---|
| 1 | `bids.content_category` | chaîne jointe par virgule (peut hériter d'un vieux code enum isolé) | décomposition + `CASE` d'égalité + recomposition |
| 2 | `package_requests.content_category` | code enum isolé, ou chaîne jointe par virgule (V143) | décomposition + `CASE` d'égalité + recomposition |
| 3 | `announcement_accepted_types.content_type` / `announcement_refused_types.content_type` | une ligne par item (texte libre) | `CASE` d'égalité, une ligne à la fois |
| 4 | `corridor_alert_content_categories.content_category` (`V148`) | une ligne par item (texte libre) | `CASE` d'égalité, une ligne à la fois |
| 5 | `trip_recurrences.accepted_categories` / `trip_templates.accepted_categories` | chaîne jointe par virgule | décomposition + `CASE` d'égalité + recomposition |
| 6 | `automation_rules.conditions` (JSONB, `V81`) | tableau d'objets `{field, operator, value}` | `jsonb_agg` sur `jsonb_array_elements`, `CASE` appliqué uniquement quand `field = 'content_type'`, reste du tableau intact |

Le mapping (9 codes enum majuscules + 14 libellés legacy → 1 des 11 libellés canoniques) est le même partout — reproduit dans chaque bloc plutôt que factorisé (PL/pgSQL non utilisé ici), et verrouillé identique au CASE Java (`ContentCategoryNormalizer`, cf. section suivante) par un test de cohérence.

Implémentation retenue pour les colonnes « chaîne jointe par virgule » (1, 2, 5) : **décomposition en items (`string_to_array` / `unnest ... WITH ORDINALITY`) + `CASE` d'égalité exacte item par item + déduplication en préservant l'ordre de première occurrence + recomposition (`string_agg`)** — pas des `REPLACE` successifs sur la chaîne complète. Un `REPLACE` en cascade n'est pas idempotent (`REPLACE('Téléphone', 'Téléphone & électronique')` rejoué sur un résultat déjà migré produirait `'Téléphone & électronique & électronique'`) et reste fragile à l'ordre de traitement (un remplacement partiel peut en corrompre un autre, ex. `Téléphone` à l'intérieur de `Téléphones & hi-fi`). La décomposition en items rend chaque comparaison exacte (`CASE lower(trim(item))`) et l'ensemble idempotent par construction : un libellé déjà canonique retombe dans la branche `ELSE` et n'est jamais retransformé, quel que soit le nombre de fois où la migration est rejouée.

Pour les colonnes « une ligne par item » (3, 4), pas de décomposition nécessaire : `CASE` d'égalité directe sur la ligne. Pour le JSONB (6) : reconstruction du tableau via `jsonb_agg`/`jsonb_array_elements WITH ORDINALITY` en préservant l'ordre des conditions et toutes les clés des objets non concernés.

Autres points :
- Les valeurs libres non reconnues (« Poissons », « Liquides ») sont **laissées intactes, casse comprise** — c'est le comportement voulu.
- Après migration, `announcement_accepted_types`/`announcement_refused_types` et `corridor_alert_content_categories` peuvent contenir des doublons (`Hi-fi` et `Téléphone` convergent tous deux vers `Téléphone & électronique`) : un `DELETE` de déduplication (sur `ctid`, ces tables n'ayant pas de PK propre à la ligne) conclut chaque bloc concerné.
- La normalisation peut aussi faire converger un libellé accepté et un libellé refusé de la même annonce vers le même canonique : la migration résout cette collision en gardant l'acceptation et en supprimant le refus (règle produit : on échoue du côté récupérable, cf. commentaire du bloc correspondant dans le SQL).

Cette migration touche des données de production. Elle est idempotente (rejouable sans dommage) et ne supprime aucune information autre que les doublons/collisions qu'elle résout elle-même.

### Normalisation à l'écriture (au-delà de la migration ponctuelle)

V171 ne normalise l'existant **qu'une fois**, au moment où elle s'exécute. Les stores mobiles ne se mettent pas à jour atomiquement : un client resté sur une ancienne version continue d'émettre des libellés/codes legacy (ex. `"Hi-fi"`) bien après la migration — ce qui re-contaminerait silencieusement les colonnes qu'elle vient de normaliser, et ferait échouer des comparaisons comme `BidContentRules.assertNotRefused` (colonne annonce déjà normalisée vs. valeur bid non normalisée).

`ContentCategoryNormalizer` (`config/`) porte donc, **en Java**, la même table de correspondance que le `CASE` SQL de V171 (source de vérité dupliquée intentionnellement, verrouillée identique par un test qui parse le SQL et compare aux entrées Java). Il expose `normalizeOne`, `normalizeJoined` (chaîne jointe par virgule) et `normalizeList`, et est appliqué **à l'écriture**, avant persistance, aux six mêmes emplacements que la migration couvre côté existant : `BidService`/`BidCheckoutService` (`contentCategory` du bid, avant le contrôle `BidContentRules.assertNotRefused`), `PackageRequestService` (`contentCategory` de la demande), `AnnouncementService` (`acceptedContentTypes`/`refusedTypes`), `AlertService` (catégories de l'alerte corridor), `TripRecurrenceService`/`TripTemplateService` (`acceptedCategories`, réinjectées à chaque exécution du scheduler ou publication depuis un modèle). `BidContentRules` et `CustomRuleConditionEvaluator` ne sont pas modifiés : ils matchent déjà correctement (par item, en lower/trim) — normaliser à l'écriture les rend corrects sans y toucher.

### Un composant de sélection, deux comportements

Partout où l'on choisit un ou plusieurs types de contenu, l'UI présente **le catalogue complet** et **permet la saisie libre**. Deux familles d'écrans :

- **Sélection multiple** (ce que le voyageur accepte / refuse, contenu d'un colis, filtres) : liste déroulante à cocher + champ « Ajouter un autre type… ». C'est le remplacement demandé des chips actuelles.
- **Sélection unique** (valeur d'une condition de règle d'automatisation dans yadony-pro) : liste déroulante simple + option « Autre valeur… » ouvrant un champ texte.

### Écrans à câbler

**yadony_app (Flutter)** — les listes en dur A–G disparaissent, remplacées par un `ContentCategoryRepository` (appel `/config/content-categories`, cache mémoire + fallback embarqué si le réseau échoue, pour ne jamais bloquer un formulaire) :

| Écran | Liste supprimée | Remplacement |
|---|---|---|
| Création de bid (bottom sheet + écran plein) | A, B | sélection multiple |
| Publication de trajet — « Ce que j'accepte » | C | sélection multiple (déjà dotée d'une saisie libre, conservée) |
| Publication de trajet — « Ce que je refuse » | — | sélection multiple (aujourd'hui saisie libre pure) |
| Filtre de recherche de trajets | D | sélection multiple |
| Édition de modèle de trajet | E | sélection multiple |
| Alerte corridor | F | sélection multiple |
| Wizard demande de colis | G (enum) | sélection multiple ; l'enum est supprimé, les icônes passent par un `iconForCode(code)` local avec repli générique |

**yadony-pro (Nuxt)** :

| Écran | Changement |
|---|---|
| `NewAnnouncementForm.vue` / `ContentTagChips.vue` | consomme la nouvelle forme `{code,label,emoji}` de l'endpoint |
| `tripTemplates.ts` (liste H) | `DEFAULT_CATEGORIES` alignée sur les libellés canoniques |
| `AutomationRuleModal.vue` | le champ `value` devient une **liste déroulante** quand `field = content_type` (aujourd'hui : `<input type="text">` pour tous les champs) — c'est ce qui rend impossible la création d'une règle qui ne matchera jamais |

**yadony-back** : catalogue structuré, endpoint, migration (six emplacements), normalisation à l'écriture (`ContentCategoryNormalizer`). `BidContentRules` et `CustomRuleConditionEvaluator` restent inchangés.

## Ce que ça corrige

Le bug d'origine est réglé de deux façons, indépendantes :

1. « Poissons » relève désormais de **Produits frais / périssables**, une catégorie que l'expéditeur peut cocher et que le voyageur peut refuser.
2. Le voyageur qui tape malgré tout « Poissons » dans yadony-pro se voit proposer une liste déroulante : une règle portant sur une valeur inexistante devient **impossible à créer**.

## Tests

- **Backend** : le catalogue servi contient 11 entrées ; aucun libellé ne contient de virgule (invariant du `split`) ; codes uniques ; l'endpoint renvoie bien `{code,label,emoji}`. Migration : test d'intégration Flyway (PostgreSQL embarqué zonky) vérifiant le mapping sur les **six emplacements** (bids, package_requests, announcement_accepted/refused_types, corridor_alert_content_categories, trip_recurrences/trip_templates.accepted_categories, automation_rules.conditions JSONB), le traitement item par item des chaînes jointes par virgule, la préservation des valeurs libres avec leur casse, la résolution des collisions accepted/refused, et l'idempotence. `ContentCategoryNormalizer` : tests unitaires des 23 mappings legacy + un test de cohérence qui parse le `CASE` SQL de V171 et vérifie qu'il reste identique à la table Java. Chaque point d'écriture (`BidService`, `BidCheckoutService`, `PackageRequestService`, `AnnouncementService`, `AlertService`, `TripRecurrenceService`, `TripTemplateService`) a un test dédié vérifiant qu'une valeur legacy soumise est persistée sous sa forme canonique. `@Size` des DTOs (`BidCheckoutRequest`, `BidRequest`, `PackageRequestCreateRequest`) porté à 500 pour absorber une multi-sélection canonique jointe (le catalogue complet joint fait 216 caractères ; deux libellés canoniques joints dépassaient déjà l'ancienne limite de 50 sur `BidCheckoutRequest`).
- **Flutter** : le repository met en cache, retombe sur le catalogue embarqué en cas d'échec réseau, et n'empêche jamais l'affichage d'un formulaire. Chaque écran câblé affiche le catalogue et accepte une saisie libre. Le test `create_bid_screen_test.dart:216` (qui recopie la liste A en dur) est réécrit pour itérer sur la source.
- **yadony-pro** : `AutomationRuleModal` affiche une liste déroulante pour `content_type` et un champ texte pour les autres champs ; `ContentTagChips` consomme la nouvelle forme.

## Hors scope (chantier séparé)

- **Contenus interdits** : argent liquide, change de devises, médicaments sur ordonnance. Nécessite une liste de blocage, un refus à la création du colis côté backend, des CGU dédiées et des avertissements douaniers (> 430 €, ANSM). Sujet juridique à part entière.
- Tarification par catégorie (forfait documents), feature « Shopper », avertissements douaniers contextuels — pistes produit issues de la même analyse, non retenues ici.
