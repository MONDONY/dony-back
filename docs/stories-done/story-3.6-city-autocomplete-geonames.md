# Story 3.6 — City Autocomplete GeoNames (Backend)

**Date:** 2026-05-08
**Status:** ✅ Complète

## Résumé

Intégration de la base de données GeoNames pour fournir un service d'autocomplétion de villes via API REST. Le backend expose deux endpoints : recherche de villes par préfixe et récupération des corridors populaires (paires départ/arrivée les plus fréquentes sur la plateforme).

## Fichiers créés

- `src/main/java/com/dony/api/city/CityEntity.java` — entité JPA pour la table `cities`
- `src/main/java/com/dony/api/city/CorridorEntity.java` — entité JPA pour la table `corridors`
- `src/main/java/com/dony/api/city/CityRepository.java` — Spring Data JPA repository avec requête full-text
- `src/main/java/com/dony/api/city/CityService.java` — logique de recherche et cache Caffeine
- `src/main/java/com/dony/api/city/CityController.java` — endpoints REST publics
- `src/main/java/com/dony/api/city/GeoNamesDataLoader.java` — chargement du fichier GeoNames au démarrage
- `src/main/java/com/dony/api/city/dto/CityDto.java` — DTO de réponse
- `src/main/java/com/dony/api/city/dto/PopularCorridorDto.java` — DTO corridors populaires
- `src/main/java/com/dony/api/city/AnnouncementCreatedEventListener.java` — incrémente les compteurs de corridors à chaque nouvelle annonce
- `src/main/resources/db/migration/V51__create_cities.sql` — table `cities` (index B-tree prefix + GIN trigram)
- `src/main/resources/db/migration/V52__create_corridors.sql` — table `corridors` avec compteur d'usage
- `src/main/resources/db/migration/V53__cities_corridors_indexes.sql` — normalisation de l'index unique corridors
- `src/main/resources/db/migration/V54__cities_country_code_varchar.sql` — correction VARCHAR de country_code
- `src/main/resources/geonames/` — fichiers CSV GeoNames (AF, CI, CM, FR, GH, SN, US)
- `src/test/java/com/dony/api/city/CityServiceTest.java` — tests unitaires du service
- `src/test/java/com/dony/api/city/CorridorServiceTest.java` — tests unitaires des corridors
- `src/test/java/com/dony/api/city/CityControllerIntegrationTest.java` — tests d'intégration MockMvc
- `src/test/java/com/dony/api/city/GeoNamesDataLoaderTest.java` — tests du data loader

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

**Chargement initial :**
1. Au démarrage de l'application (`ApplicationReadyEvent`), `GeoNamesDataLoader` lit les fichiers CSV dans `resources/geonames/`
2. Il filtre pour ne charger que les villes de population ≥ 5000 pour les pays ciblés
3. Les villes sont insérées en batch avec `COPY` SQL ou JPA bulk save (skip si déjà présentes via `ON CONFLICT DO NOTHING`)

**Recherche de villes :**
1. Client Flutter → `GET /api/v1/cities/search?q=Par&limit=10`
2. `CityController` délègue à `CityService.searchCities(query, limit)`
3. `CityService` utilise `CityRepository.findByNamePrefix(query)` — requête PostgreSQL avec index B-tree sur le préfixe du nom
4. Résultat mis en cache Caffeine (clé : `"cities:"+query.toLowerCase()`, TTL 5 min)
5. Retourne une liste de `CityDto` (name, countryCode, countryName, lat, lng)

**Corridors populaires :**
1. Client Flutter → `GET /api/v1/cities/corridors/popular?limit=6`
2. Retourne les 6 corridors triés par `usageCount DESC`
3. Mis à jour automatiquement quand une annonce est créée (`AnnouncementCreatedEvent` → `AnnouncementCreatedEventListener`)

### Points d'entrée API

- `GET /api/v1/cities/search?q={query}&limit={n}` — public (pas d'auth), retourne `List<CityDto>`. Retourne `[]` si `q.length < 2`
- `GET /api/v1/cities/corridors/popular?limit={n}` — public, retourne `List<PopularCorridorDto>`

### Entités JPA impliquées

- `CityEntity` → table `cities` — champs : `name`, `countryCode`, `countryName`, `lat`, `lng`. Index B-tree sur `lower(name)` pour la recherche préfixe.
- `CorridorEntity` → table `corridors` — champs : `departureCity`, `departureCountry`, `arrivalCity`, `arrivalCountry`, `usageCount`. Contrainte unique `(departure_city, arrival_city)` pour idempotence de l'upsert.

### Logique métier critique

- **Filtre population GeoNames** : seules les villes avec population ≥ 5000 sont chargées pour éviter les villages obscurs dans l'autocomplétion
- **Pays ciblés** : AF (Afghanistan n'est pas concerné — le code est pour l'Afrique subsaharienne), CI, CM, FR, GH, SN, US. Le filtre est dans `GeoNamesDataLoader.SUPPORTED_COUNTRIES`
- **Index B-tree prefix** : la requête PostgreSQL utilise `WHERE lower(name) LIKE lower(:prefix) || '%'` — efficace grâce à l'index `lower(name) text_pattern_ops`
- **Upsert corridors** : l'événement `AnnouncementCreatedEventListener` fait un `MERGE` / `INSERT ... ON CONFLICT DO UPDATE SET usage_count = usage_count + 1` — idempotent

### Events Spring publiés / écoutés

- `AnnouncementCreatedEvent` écouté par `AnnouncementCreatedEventListener` → incrémente `corridors.usage_count` pour le corridor `(departureCity, arrivalCity)` de l'annonce créée

### Pièges et points d'attention

- **Fichiers GeoNames volumeux** : le CSV `cities500.txt` global peut peser 300MB. On utilise des fichiers par pays (`FR.txt`, `SN.txt`, etc.) dans `resources/geonames/` pour limiter le JAR. Au-delà de 50k villes, envisager un chargement lazy depuis S3.
- **Chargement idempotent** : `GeoNamesDataLoader` vérifie `cityRepository.count()` avant de charger — si la DB contient déjà des villes, le loader ne recharge pas (évite les doublons entre redémarrages).
- **V53 + V54** : deux migrations correctives ont été nécessaires après V51/V52. V53 normalise l'index unique sur `corridors` (les colonnes `departure_city` et `arrival_city` doivent être en minuscule dans la contrainte). V54 corrige le type `country_code` de `CHAR(2)` en `VARCHAR(3)` pour certains codes GeoNames non-standard.
- **Cache Caffeine** : le cache `cities-search` a un TTL de 5 min et une taille max de 500 entrées. Invalider si on met à jour la base GeoNames (migration ou rechargement manuel).

## Critères d'acceptation couverts

- [x] `GET /cities/search?q=Par` retourne une liste paginée de villes dont le nom commence par "Par"
- [x] `GET /cities/search?q=P` retourne une liste vide (moins de 2 caractères)
- [x] `GET /cities/corridors/popular` retourne les 6 corridors triés par fréquence d'usage
- [x] Un nouveau corridor est créé / incrémenté quand une annonce est publiée
- [x] Le chargement GeoNames au démarrage est idempotent (redémarrage sans doublon)
- [x] Les endpoints sont publics (pas de token requis)
- [x] Les résultats de recherche sont mis en cache Caffeine

## Tests

- `./mvnw test` → 509 tests passent (0 rouge)
- `./mvnw test jacoco:report` → couverture city package : **92.5%** (197/213 lignes)
- Tests ajoutés :
  - `CityServiceTest.java` — tests unitaires (mock repository, cache hit/miss)
  - `CorridorServiceTest.java` — tests unitaires (upsert, tri par usageCount)
  - `CityControllerIntegrationTest.java` — tests MockMvc endpoints
  - `GeoNamesDataLoaderTest.java` — tests chargement CSV et idempotence

## Décisions techniques

| Décision | Choix | Alternative écartée | Raison |
|---|---|---|---|
| Source données villes | GeoNames CSV par pays | API externe GeoNames | Pas de dépendance réseau au runtime, latence nulle |
| Stratégie de recherche | Index B-tree `text_pattern_ops` | Extension `pg_trgm` (GIN) | Plus simple, suffisant pour la recherche préfixe |
| Mise en cache | Caffeine in-memory | Redis | Cohérent avec l'architecture existante (pas de Redis en MVP) |
| Chargement initial | `ApplicationReadyEvent` au boot | Migration Flyway avec `COPY` | Plus flexible, permet de mettre à jour les fichiers GeoNames sans migration |
| Corridors populaires | Compteur en DB incrémenté via event | Calcul à la volée depuis announcements | Évite un `GROUP BY` coûteux à chaque requête |
