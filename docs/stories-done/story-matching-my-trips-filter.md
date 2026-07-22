# Story — Filtre `matchingMyTrips` sur la recherche de demandes (Backend)

**Date:** 2026-07-22
**Status:** ✅ Complète

## Résumé
`GET /package-requests` accepte désormais un paramètre `matchingMyTrips=true` qui restreint
la recherche paginée aux demandes de colis compatibles avec les trajets actifs du voyageur
authentifié, triées par score de compatibilité décroissant. Cette recherche remplace
l'usage applicatif de `GET /travelers/me/matching-requests` (non paginé, dupliqué par
couple trajet/demande), qui est déprécié mais conservé.

## Fichiers créés
- `docs/stories-done/story-matching-my-trips-filter.md` — ce document.

## Fichiers modifiés
- `src/main/java/com/dony/api/matching/MatchingService.java` — ajout du record public
  `MatchInfo(UUID requestId, UUID tripId, LocalDate tripDepartureDate, int matchScore)`
  et de `findBestMatchByRequestId(UUID travelerId)`, variante dédupliquée de
  `findMatchingRequests` qui ne garde qu'une entrée par demande (le meilleur score) et
  retourne une `LinkedHashMap` triée par score décroissant.
- `src/main/java/com/dony/api/requests/specification/PackageRequestSpecifications.java` —
  ajout de `idIn(Collection<UUID>)`, qui restreint aux demandes dont l'id figure dans la
  collection ; une collection vide ou nulle produit `cb.disjunction()` (aucun résultat),
  jamais `cb.conjunction()` (tout).
- `src/main/java/com/dony/api/requests/dto/PackageRequestSearchResponse.java` — trois
  champs nullables ajoutés en fin de record (`matchScore`, `matchedTripId`,
  `matchedTripDepartureDate`) et méthode `withMatch(MatchingService.MatchInfo)` qui produit
  une copie enrichie. Champs `null` en dehors du filtre `matchingMyTrips`.
- `src/main/java/com/dony/api/requests/service/PackageRequestService.java` — ajout de
  `searchMatchingMyTrips(Specification<PackageRequestEntity>, Pageable, UUID callerId)` :
  interroge `MatchingService.findBestMatchByRequestId`, court-circuite si la map est vide
  (aucune requête SQL), sinon restreint la spec avec `idIn(matches.keySet())`, charge les
  entités, les enrichit via `withMatch`, trie par score décroissant et pagine en mémoire.
- `src/main/java/com/dony/api/requests/controller/PackageRequestController.java` —
  paramètre `matchingMyTrips` sur `GET /package-requests`, aiguillé avant la branche
  géographique (`lat`/`lng`) et avant la recherche par défaut.
- `src/main/java/com/dony/api/matching/TravelerStatsController.java` — `@Deprecated` et
  Javadoc sur `getMatchingRequests()` (`GET /travelers/me/matching-requests`), pointant
  vers le nouveau paramètre. L'endpoint n'est pas supprimé : `MatchingService` reste
  utilisé par `findTravelersMatchingPackage` (notification temps réel à la création d'une
  demande) et par `AlertService`.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. Le client (voyageur authentifié, `ROLE_TRAVELER`) appelle
   `GET /package-requests?matchingMyTrips=true` avec, en option, les autres filtres
   habituels (corridor, dates, poids, taille de colis, urgence).
2. Le contrôleur construit la `Specification` de base (statut ouvert + filtres optionnels)
   puis, si `matchingMyTrips=true`, délègue à
   `PackageRequestService.searchMatchingMyTrips` au lieu de `search`/`searchNearMe`.
3. Le service demande à `MatchingService.findBestMatchByRequestId(callerId)` la map des
   meilleurs matchs du voyageur (un trajet actif compatible par demande, dédupliqué,
   trié par score décroissant).
4. Si la map est vide (aucun trajet actif, ou aucun match), retour immédiat d'une page
   vide sans toucher la base — pas de requête SQL inutile pour un voyageur sans trajet.
5. Sinon, la `Specification` reçue est combinée avec `idIn(matches.keySet())`, les
   entités correspondantes sont chargées en une fois (`repository.findAll(restricted)`,
   sans pagination SQL), mappées en `PackageRequestSearchResponse`, enrichies via
   `withMatch(matches.get(id))`, triées par `matchScore` décroissant, puis paginées en
   mémoire (`sublist` sur les bornes calculées depuis `Pageable`).
6. La réponse est une `Page<PackageRequestSearchResponse>` standard, où chaque élément
   porte en plus `matchScore`, `matchedTripId` et `matchedTripDepartureDate`.

### Points d'entrée API
- `GET /package-requests?matchingMyTrips=true` (+ filtres optionnels usuels) — `ROLE_TRAVELER`
  requis (`@PreAuthorize` déjà présent sur la méthode `search` du contrôleur, inchangé).
  Sans le paramètre (ou `false`), comportement strictement identique à avant : la
  fonctionnalité est additive et n'affecte aucun appelant existant.
- `GET /travelers/me/matching-requests` — **déprécié** (`@Deprecated(since = "2026-07-22")`),
  toujours fonctionnel, `ROLE_TRAVELER` requis. Retourne un DTO par couple (trajet,
  demande), non paginé.

### Entités JPA impliquées
- `PackageRequestEntity` (table `package_requests`) — aucune modification de schéma.
  Filtrée via `Specification` (`status`, `id`, corridor, date, poids, taille).
- `AnnouncementEntity` (table `announcements`) — lue en lecture seule par
  `MatchingService` (`findActiveByTravelerId`) pour déterminer les trajets actifs du
  voyageur ; aucune modification.
- Aucune nouvelle entité, aucune migration Flyway.

### Logique métier critique
- **Le score de match** est calculé par `MatchingService` (poids, budget/kg, proximité de
  date) — logique préexistante, réutilisée telle quelle, non dupliquée en SQL.
- **Pas de tri SQL** : `findAll(restricted)` sans `Pageable` charge toutes les demandes
  matchées avant de trier/paginer en mémoire. Acceptable car l'ensemble est borné par le
  nombre de trajets actifs du voyageur × demandes du corridor, du même ordre de grandeur
  que ce que retournait déjà `findMatchingRequests` sans pagination.
- **Injection assumée `requests → matching`** (`PackageRequestService` dépend de
  `MatchingService`), alors que la règle par défaut du `CLAUDE.md` impose les Spring
  Events entre packages. Justification : le résultat est une lecture synchrone
  unidirectionnelle nécessaire à la construction immédiate de la réponse HTTP — un event
  ne convient pas quand l'appelant attend une valeur de retour. Pas de cycle
  (`matching` n'importe rien de `requests`).

### Events Spring publiés / écoutés
Aucun. Cette story n'introduit ni ne modifie d'event ; le flux est une lecture
synchrone de bout en bout.

### Pièges et points d'attention
1. **Déduplication par demande.** `MatchingService.findMatchingRequests` (utilisé par
   l'ancien endpoint) produit un DTO par couple (trajet, demande) : une demande
   compatible avec deux trajets actifs du même voyageur y figure deux fois. C'est sans
   conséquence sur l'écran qui consomme des couples, mais aurait produit des doublons
   dans une page de résultats. `findBestMatchByRequestId` a été introduit spécifiquement
   pour dédupliquer (ne garder que le meilleur score par demande) — ne pas réutiliser
   `findMatchingRequests` pour un nouveau besoin de liste paginée.
2. **Court-circuit sur map vide.** Si `findBestMatchByRequestId` retourne une map vide
   (voyageur sans trajet actif, ou aucun match), `searchMatchingMyTrips` retourne
   directement une `Page` vide sans exécuter de requête SQL sur `package_requests`. Ne
   pas retirer ce court-circuit pour "simplifier" le code : sans lui, `idIn(Set.of())`
   fait déjà le travail correctement (voir point 5), mais on paierait une requête SQL
   inutile à chaque appel d'un voyageur sans trajet.
3. **Précédence sur la recherche géographique.** Dans le contrôleur, `matchingMyTrips`
   est vérifié *avant* la branche `lat`/`lng` (recherche « près de moi »). Les deux
   filtres ne se composent pas : l'un trie par score de compatibilité, l'autre par
   distance croissante. Si un jour on veut les combiner, il faudra explicitement décider
   quel tri prime, ou en fabriquer un troisième — ne pas supposer qu'ajouter les deux
   paramètres en même temps fait quoi que ce soit d'utile aujourd'hui (le géo est
   silencieusement ignoré).
4. **Injection `requests → matching` assumée**, contraire à la règle par défaut
   « cross-package = Spring Events uniquement » du `CLAUDE.md`. Voir justification
   ci-dessus (§ Logique métier critique). À ne pas prendre comme précédent généralisable :
   c'est un cas où la synchronicité du retour HTTP l'exige, pas une exception ouverte à
   toute dépendance inter-packages.
5. **`idIn` avec collection vide ne matche rien, jamais tout.** `PackageRequestSpecifications
   .idIn` retourne `cb.disjunction()` (faux pour toute ligne) si la collection est vide ou
   nulle. C'est **volontaire et critique** : une implémentation naïve qui retournerait
   `cb.conjunction()` (comme le font `corridor`/`dateRange`/`maxWeight`/`parcelSize` quand
   leurs paramètres sont absents) ferait qu'un voyageur sans trajet actif verrait *toutes*
   les demandes de la plateforme au lieu d'aucune. Ne pas copier le pattern
   `conjunction()`-par-défaut des autres specs sur `idIn`.
6. **Le tri doit rester un ordre total strict — sinon la pagination ment.**
   `searchMatchingMyTrips` trie en mémoire le résultat d'un `findAll(spec)` qui n'a
   pas d'`ORDER BY` : Postgres est libre de rendre les lignes dans un ordre différent
   d'une requête à l'autre. Or dans ce chemin le score discrimine mal — `dateScore`
   vaut *toujours* 25 (`fitsDate` a déjà garanti que l'écart est dans la tolérance) et
   `budgetScore` ne prend que 3 valeurs — donc seul `weightScore` sépare : les ex æquo
   sont la règle, pas l'exception. Avec un simple tri par score (stable), les ex æquo
   sortent dans l'ordre Postgres, et une même demande peut apparaître sur deux pages
   ou sur aucune. D'où le comparateur `matchOrder` : **score décroissant → `createdAt`
   décroissant → `id`**, l'`id` en dernier recours garantissant l'ordre total. Ne pas
   retirer les départages « parce que le score suffit ». Même raison côté
   `MatchingService.findBestMatchByRequestId`, dont la map ordonnée départage aussi par
   `requestId` (l'itération d'une `HashMap` n'est pas un ordre).
7. **Le filtre expose `OPEN` **et** `NEGOTIATING`, comme la recherche standard.**
   `PackageRequestSpecifications.openOnly()` retient les deux statuts. Le filtre doit
   faire pareil, sinon le même endpoint expose deux ensembles de statuts selon la valeur
   du booléen : une demande passe à `NEGOTIATING` dès qu'un voyageur *quelconque* ouvre
   un fil (`NegotiationService`), et le voyageur qui négocie verrait la demande
   disparaître de sa propre liste filtrée au refresh suivant. D'où
   `PackageRequestRepository.findOpenOrNegotiatingByCorridor`, consommé **uniquement**
   par `findBestMatchByRequestId`. `findOpenByCorridor` (statut `OPEN` seul) est
   **volontairement laissé intact** : il alimente le digest d'alertes corridor
   (`AlertService`), qui ne doit notifier que des demandes encore strictement ouvertes.
   Ne pas fusionner les deux méthodes.
8. **Mapper la page, pas l'ensemble.** `buildBatchMaps` déclenche une URL S3 présignée
   (HMAC-SHA256) par photo et un `avatarUrl` par expéditeur. `searchMatchingMyTrips`
   trie et découpe donc les **entités**, puis n'appelle `buildBatchMaps` que sur la page.
   Ne pas remonter le mapping avant le `subList` « pour simplifier » : 2 000 demandes ×
   4 photos = 8 000 signatures calculées pour en renvoyer 80. `totalElements` reste le
   total filtré (`sorted.size()`), pas la taille de la page.
9. **Pagination : arithmétique en `long`.** `offset + pageSize` déborde en `int` sur
   `?page=1&size=2147483647` (paramètre client), `subList` lèverait alors une exception
   → HTTP 500. Le calcul des bornes se fait en `long` avant le cast final.
10. **La règle de match a un point d'extension unique.** `MatchingService.matches(request,
    announcement)` est partagée par `findMatchingRequests` et `findBestMatchByRequestId`.
    Ajouter un critère (ex. mode de transport, catégorie) doit se faire là, pas dans une
    des deux boucles : elles avaient divergé silencieusement avant cette factorisation.

## Critères d'acceptation couverts
- [x] `GET /package-requests?matchingMyTrips=true` retourne les demandes compatibles avec
      les trajets actifs du voyageur, une seule fois chacune, triées par score décroissant.
- [x] Un voyageur sans trajet actif reçoit une page vide (`content: []`, `totalElements: 0`),
      sans requête SQL sur `package_requests` et sans jamais voir la totalité des demandes.
- [x] Le paramètre se combine avec les filtres existants (corridor, dates, poids, taille).
- [x] Sans le paramètre (ou `matchingMyTrips=false`), le comportement de
      `GET /package-requests` est strictement inchangé (non-régression vérifiée par
      `PackageRequestSearchMatchingIntegrationTest`).
- [x] `GET /travelers/me/matching-requests` reste fonctionnel, marqué `@Deprecated` avec
      Javadoc pointant vers le remplaçant, sans suppression ni changement de contrat.

## Tests
- `./mvnw test` → **2630 tests, 0 échec, 0 erreur, 7 ignorés** (suite complète du repo).
- `./mvnw test jacoco:report` → couverture globale relevée sur
  `target/site/jacoco/index.html` : **85 % instructions** (66 732 / 78 330, `11 598` manquées),
  **72 % branches**, **87 % lignes**. En dessous du seuil de 90 % visé par le `CLAUDE.md` —
  dette de couverture préexistante sur le repo, non introduite par cette story ; les
  classes touchées par ce plan (`MatchingService`, `PackageRequestSpecifications`,
  `PackageRequestSearchResponse`, `PackageRequestService`, `PackageRequestController`)
  sont couvertes par les tests dédiés listés ci-dessous.
- Tests ajoutés par les tâches précédentes de ce plan (tous verts) :
  - `MatchingServiceTest` — 3 tests sur `findBestMatchByRequestId` (déduplication, tri par
    score, map vide).
  - `PackageRequestSpecificationsTest` — 2 tests sur `idIn` (collection non vide, collection
    vide ne matche rien).
  - `PackageRequestSearchResponseMatchTest` — 2 tests sur `withMatch` et la nullabilité des
    champs de match.
  - `PackageRequestServiceMatchingTest` — 3 tests sur `searchMatchingMyTrips` (court-circuit
    map vide, restriction + tri, pagination en mémoire).
  - `PackageRequestSearchMatchingIntegrationTest` — 4 tests d'intégration MockMvc sur
    `GET /package-requests?matchingMyTrips=true` (dont non-régression sans le paramètre).
- Aucun test ajouté dans cette tâche 6 : la dépréciation est une annotation + Javadoc sans
  changement de comportement, déjà couverte par `MatchingRequestsEndpointTest` existant.

## Décisions techniques
- **Ne pas supprimer `GET /travelers/me/matching-requests`.** `MatchingService` reste
  utilisé par `findTravelersMatchingPackage` (notification temps réel à la création d'une
  demande) et par `AlertService` : retirer l'endpoint public est un changement de contrat
  qui mérite sa propre PR, après vérification des versions d'app en circulation qui
  pourraient encore l'appeler. `@Deprecated(since = "2026-07-22")` documente l'intention
  sans rien casser.
- **Tri et pagination en mémoire plutôt qu'en SQL** pour `searchMatchingMyTrips`, faute de
  pouvoir exprimer le score de compatibilité de `MatchingService` comme une clause `ORDER BY`
  sans dupliquer la logique métier (poids, budget/kg, proximité de date) en SQL. Alternative
  écartée : recalculer le score en base (vue matérialisée ou requête native) — complexité et
  risque de divergence avec `MatchingService` jugés disproportionnés pour un volume borné par
  les trajets actifs d'un seul voyageur.
- **`idIn` en `cb.disjunction()` par défaut**, à rebours du pattern `cb.conjunction()`
  utilisé par les autres specs de ce fichier quand leur paramètre est absent. Décision
  délibérée : une spec de filtrage par ids n'a de sens que si elle restreint ; la traiter
  comme les filtres optionnels usuels aurait ouvert une faille de confidentialité (fuite de
  toutes les demandes à un voyageur sans trajet).
- **Précédence de `matchingMyTrips` sur la recherche géographique** dans le contrôleur,
  plutôt que de les rendre combinables. Les deux tris (score vs distance) ne se composent
  pas sans arbitrage produit explicite ; combiner silencieusement aurait donné un résultat
  dont l'ordre ne correspond à aucune des deux promesses.
