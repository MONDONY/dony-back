# Moteur d'exécution des règles personnalisées (SI→ALORS) — Design

## Contexte

Le chantier précédent (`2026-07-11-automation-engine-design.md`, PR #96 dony-back / PR #10 dony-pro) a livré le moteur d'exécution des **règles préconfigurées**. Les **règles personnalisées** (SI→ALORS, créées via `AutomationRuleModal.vue`) étaient explicitement hors scope : le CRUD fonctionne (création, édition, toggle, suppression, affichage), mais aucun moteur ne les évalue.

**Bug constaté en production par l'utilisateur** : une règle custom « Refuser colis avec des aliments » (SI Type de contenu = Poissons → ALORS Refuser automatiquement), active, n'a eu aucun effet quand un expéditeur a soumis une demande correspondante. Comportement attendu vu le scope précédent, mais surprise silencieuse côté produit — objet de ce chantier.

**Dépendance** : ce chantier s'appuie sur le code de la branche `feature/automation-engine` (non encore mergée) — `AutomationActionExecutor`, `AutomationBidListener`, `BidService.acceptBidBySystem/rejectBidBySystem`. La branche `feature/custom-rules-engine` est donc basée sur `feature/automation-engine`, pas sur `main`. La PR résultante sera soit stackée sur #96, soit rebasée sur `main` après merge de #96.

## Modèle de données existant (aucun changement de schéma)

`AutomationRuleEntity` porte déjà tout le nécessaire :

- `ruleType = "CUSTOM"` (vs `"PRESET"`)
- `conditions` : JSONB `List<Map<String,Object>>` — chaque entrée `{field, operator, value}` :
  - `field` ∈ `sender_rating | weight_kg | corridor | content_type | capacity_free_kg | hours_before_departure` (contraint par un `<select>` côté front)
  - `operator` ∈ `gte | lte | eq` (contraint par un `<select>` côté front)
  - `value` : **toujours une chaîne libre** (`<input type="text">` côté front), même pour les champs numériques
- `action` : JSONB `Map<String,Object>` — `{type, message?}` avec `type` ∈ `auto_accept | auto_reject | trigger_search | send_alert | invite_sender | close_announcement`

## Périmètre (décisions validées avec l'utilisateur)

1. **Actions supportées : `auto_accept` et `auto_reject` uniquement.** Les 4 autres types (`send_alert`, `trigger_search`, `invite_sender`, `close_announcement`) restent hors scope — une règle custom active portant l'un de ces types est ignorée par le moteur (aucune action, aucun historique), comportement identique à aujourd'hui.
2. **Conditions multiples = ET strict.** Toutes les conditions d'une règle doivent matcher pour que l'action se déclenche.
3. **Comparaison texte (opérateur `eq` sur `content_type`/`corridor`) : insensible à la casse et aux espaces de bord** (`trim()` + `toLowerCase(Locale.ROOT)` des deux côtés). Pas de pliage d'accents ni de recherche partielle (« Poissons » ≠ « poisson frais ») — hors scope, documenté.
4. **Priorité inter-règles : tout refus gagne toujours sur toute acceptation**, que la règle soit preset ou custom, indépendamment de l'ordre de création. Généralisation de la logique déjà en place entre les deux presets (refus surpoids > accept confiance).
5. **Plafond quotidien partagé** : les actions custom passent par le même `AutomationActionExecutor.tryExecuteBidAction` et comptent dans le même `DAILY_ACTION_CAP = 20` par voyageur que les actions preset (l'historique est commun, `countTodayActions` compte tout). Décision technique par cohérence — un seul garde-fou global.

## Architecture

### Déclencheur

Même point d'entrée que les presets bid-scoped : `BidCreatedEvent` (publié après autorisation de paiement, bid en `PAYMENT_ESCROWED`). **On étend `AutomationBidListener`** plutôt que créer un listener frère : une seule passe d'évaluation par bid, la priorité refus>accept se décide au même endroit, pas de course entre deux listeners sur le même événement.

### Nouveau composant : `CustomRuleConditionEvaluator`

Classe dédiée dans `com.dony.api.automation` (bean Spring sans état, ou classe statique pure — au choix de l'implémentation, pure de préférence pour la testabilité) :

```java
boolean matches(AutomationRuleEntity rule, BidEvaluationContext ctx)
```

où `BidEvaluationContext` est un petit record portant les valeurs résolues une seule fois par le listener :

```java
record BidEvaluationContext(
    BigDecimal weightKg,          // event.getWeightKg()
    String corridor,              // event.getCorridor() — format "{départ} → {arrivée}"
    String contentCategory,       // bid.getContentCategory() — chargé via BidRepository
    BigDecimal senderRating,      // sender.getAverageRating() — peut être null
    BigDecimal capacityFreeKg,    // announcement.getAvailableKg()
    Long hoursBeforeDeparture     // Duration.between(now, announcement.getDepartureAt()).toHours(), null si departureAt null
) {}
```

`matches` retourne `true` uniquement si **toutes** les conditions de la règle sont satisfaites. Évaluation d'une condition :

| `field` | Type | Source | Opérateurs valides |
|---|---|---|---|
| `sender_rating` | numérique | `UserEntity.averageRating` | gte, lte, eq |
| `weight_kg` | numérique | `BidCreatedEvent.weightKg` | gte, lte, eq |
| `capacity_free_kg` | numérique | `AnnouncementEntity.availableKg` | gte, lte, eq |
| `hours_before_departure` | numérique | dérivé de `announcement.departureAt` | gte, lte, eq |
| `corridor` | texte | `BidCreatedEvent.corridor` | eq uniquement |
| `content_type` | texte | `BidEntity.contentCategory` | eq uniquement |

**Règles de robustesse (fail-safe : dans le doute, ne pas agir)** :

- Valeur du contexte `null` (ex. expéditeur sans note, colis sans catégorie déclarée) → condition **non satisfaite** → la règle ne matche pas.
- `value` de la condition non parsable en `BigDecimal` pour un champ numérique → condition non satisfaite + log `warn` (une seule fois par évaluation, pas de spam).
- Opérateur `gte`/`lte` sur un champ texte → condition non satisfaite + log `warn`.
- `field` ou `operator` inconnu (donnée corrompue ou future version front) → condition non satisfaite + log `warn`.
- Liste `conditions` vide → la règle ne matche **jamais** (jamais d'action sur règle sans condition).

Comparaison numérique : `BigDecimal.compareTo` (jamais `equals`, qui distingue les échelles). Comparaison texte : normalisation `trim().toLowerCase(Locale.ROOT)` des deux côtés avant `equals`.

### Intégration dans `AutomationBidListener.onBidCreated`

Nouvel ordonnancement (remplace le flux actuel, à comportement preset identique) :

1. Charger les règles du voyageur (déjà fait, `findByTravelerIdOrderByCreatedAtAsc`).
2. Résoudre le `BidEvaluationContext` : charger `BidEntity` via `BidRepository.findById(event.getBidId())` (nouvelle injection — on n'élargit pas le constructeur de `BidCreatedEvent`, partagé avec d'autres listeners) + `UserEntity` expéditeur + `AnnouncementEntity` (déjà chargés aujourd'hui). Bid introuvable → log warn + return.
3. **Phase refus** — dans cet ordre, s'arrêter à la première action exécutée :
   a. Preset `auto_reject_overweight` (logique actuelle inchangée).
   b. Règles custom actives (`ruleType=CUSTOM`, `enabled=true`, `action.type=auto_reject`), par `createdAt` croissant, première qui matche → `executor.tryExecuteBidAction(rule, …, "CUSTOM_AUTO_REJECT", () -> bidService.rejectBidBySystem(bidId, travelerId, motif))`.
   - Motif de refus transmis à l'expéditeur : le `message` de l'action s'il est non vide, sinon « Refusé automatiquement par une règle du voyageur : {nom de la règle}. »
4. **Phase acceptation** — uniquement si **aucune règle de refus n'a matché** (voir la nuance sémantique au point 5) :
   a. Preset `auto_accept_trusted` (logique actuelle inchangée, y compris son re-check poids).
   b. Règles custom `auto_accept`, par `createdAt` croissant, première qui matche → `"CUSTOM_AUTO_ACCEPT"` + `acceptBidBySystem`.
5. **Nuance sémantique importante** : la phase acceptation est bloquée dès qu'une règle de refus a **matché**, même si l'exécution du refus a échoué (plafond atteint, erreur) — un colis qu'une règle voulait refuser ne doit jamais être auto-accepté par une autre. Concrètement : `boolean rejectMatched` (une règle de refus a matché) distinct de `rejectExecuted`. La phase 4 est conditionnée à `!rejectMatched`. C'est un durcissement volontaire par rapport au comportement preset actuel (où `rejected` = résultat d'exécution) ; le comportement preset est aligné sur cette nouvelle sémantique dans le même mouvement.
6. Alerte dernière minute (preset 6) : inchangée, indépendante des phases accept/reject.

Une seule action bid (accept XOR reject) par bid, comme aujourd'hui. Les règles custom `send_alert`/`trigger_search`/`invite_sender`/`close_announcement` sont filtrées en amont et jamais évaluées.

**Contrainte transactionnelle (héritée du chantier précédent, à respecter absolument)** : ni `AutomationBidListener.onBidCreated`, ni aucune méthode orchestrant un appel à `AutomationActionExecutor.tryExecuteBidAction`, ne doit porter `@Transactional` (risque `UnexpectedRollbackException` sur transaction imbriquée avec `BidService.*BySystem`). `CustomRuleConditionEvaluator` est pur, sans accès base, donc non concerné.

### Historique

Chaque action custom écrit une ligne `automation_history` via le chemin existant : `ruleLabel` = `rule.getName()` (le nom saisi par l'utilisateur, ex. « Refuser colis avec des aliments »), `actionTaken` = `CUSTOM_AUTO_REJECT`/`CUSTOM_AUTO_ACCEPT`, `result` = SUCCESS/FAILURE/CAP_REACHED. Aucune ligne pour une règle qui ne matche pas (pas de bruit).

### Frontend (dony-pro)

**Aucun changement requis.** La création/édition de règles custom fonctionne déjà (`AutomationRuleModal.vue`), l'historique affiche déjà les entrées via `ruleLabel`. Le chantier est 100 % backend.

## Tests

TDD strict. Nouveau code visé ≥ 90 %.

- **Unit `CustomRuleConditionEvaluator`** (le gros de la valeur, pur, rapide) :
  - chaque `field` × chaque opérateur valide (match et non-match) ;
  - normalisation texte : « Poissons » vs `" poissons "` → match ; « Poissons » vs « poisson frais » → non-match ;
  - ET strict : 2 conditions dont 1 seule satisfaite → non-match ;
  - fail-safe : valeur contexte null, `value` non parsable, gte/lte sur texte, field/operator inconnus, conditions vides → non-match systématique ;
  - `BigDecimal` avec échelles différentes (`4.0` vs `4.00`) → eq match.
- **Unit `AutomationBidListener`** (Mockito, style existant) :
  - custom reject matche → `rejectBidBySystem` appelé avec le bon motif (message custom puis fallback) ;
  - custom reject matche ET preset accept matche → refus exécuté, accept jamais appelé ;
  - preset reject matche ET custom accept matche → refus exécuté, accept jamais appelé ;
  - refus matche mais plafond atteint (`tryExecuteBidAction` renvoie false) → accept quand même bloqué (`rejectMatched`) ;
  - deux customs reject matchent → seule la plus ancienne (createdAt) exécutée ;
  - custom accept matche, aucun refus → `acceptBidBySystem` appelé ;
  - règle custom `send_alert` active qui « matcherait » → ignorée, aucun appel ;
  - bid introuvable → return silencieux (log), aucune action ;
  - non-régression : les 3 presets (1/2/6) se comportent comme avant.

## Hors scope (explicitement)

- Actions custom `send_alert`, `trigger_search`, `invite_sender`, `close_announcement`
- Logique OU entre conditions, opérateurs `contains`/regex, pliage d'accents
- Vocabulaire contrôlé pour `content_type` (le champ reste du texte libre des deux côtés — amélioration produit future : liste fermée partagée expéditeur/voyageur)
- Tout changement front (dony-pro) et Flutter (dony_app)
