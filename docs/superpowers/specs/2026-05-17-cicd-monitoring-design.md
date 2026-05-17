# CI/CD & Monitoring du backend dony — Design

**Date:** 2026-05-17
**Statut:** Validé (design approuvé, prêt pour le plan d'implémentation)

## Contexte

Le backend `dony-back` (Spring Boot 3.5, Java 21) doit disposer d'une chaîne
CI/CD complète et d'une observabilité de production. Une CI/CD existe déjà
(`quality.yml`, `deploy.yml`, `owasp-weekly.yml`) mais est reconstruite de zéro
pour intégrer deux environnements et une stratégie de promotion explicite.

### Décisions cadrées avec l'utilisateur

| Sujet | Décision |
|---|---|
| Objectif | Reconstruire CI/CD + monitoring depuis zéro |
| Infrastructure | Deux VPS OVH : `staging` + `prod` |
| Flux de déploiement | `main` → staging automatique ; prod via déclenchement manuel + approbation |
| Périmètre monitoring | Métriques + dashboards, agrégation de logs, alertes, uptime externe |
| Hébergement monitoring | Grafana Cloud (free tier) |
| Canal d'alertes | Discord |
| Métriques métier | Techniques + instrumentation métier complète (tous les packages) |

## Objectifs

1. Quality gates automatiques sur chaque PR et push sur `main`.
2. Déploiement continu sur staging à chaque merge sur `main`.
3. Promotion en production manuelle, contrôlée, du binaire **déjà validé en staging**.
4. Observabilité complète en production via Grafana Cloud : métriques, logs,
   alertes Discord, monitoring uptime externe.
5. Aucune surcharge des VPS (RAM, maintenance) : monitoring hébergé.

## Non-objectifs (YAGNI)

- Pas de Kubernetes ni d'orchestration managée — Docker Compose suffit pour le MVP.
- Pas de stack de monitoring self-hosted (Prometheus/Grafana sur VPS).
- Pas de tracing distribué (un seul service, peu de valeur en MVP).
- Pas de base de données managée — PostgreSQL en conteneur, comme l'existant.
- Pas de blue/green ni de canary — déploiement par remplacement de conteneur.

## Architecture

```
PR / push ─────▶ CI : tests+JaCoCo, SpotBugs, Trivy, Hadolint
                          │
                    merge sur main
                          ▼
            Build image Docker ──▶ ghcr.io (tags sha-xxx + staging)
                          │
                          ▼
            Deploy STAGING (auto, env GitHub `staging`)
                          │
              workflow_dispatch manuel + approbation
              (env GitHub `production`)
                          ▼
            Deploy PROD (promotion du tag sha-xxx validé)

VPS staging ─┐
             ├─▶ agent Grafana Alloy ─▶ Grafana Cloud ─▶ Discord #alertes-prod
VPS prod    ─┘                              │
                                            └─▶ Synthetic Monitoring (uptime externe)
```

## Composant 1 — Pipeline CI/CD (GitHub Actions)

On conserve GitHub Actions et le registre `ghcr.io`. Quatre workflows
remplacent les trois actuels.

### `ci.yml` — Quality Gates

- **Déclencheur :** `pull_request` vers `main` + `push` sur `main`.
- **Jobs :**
  - Tests unitaires + JaCoCo (`./mvnw test -Dspring.profiles.active=test`).
  - SpotBugs (`spotbugs:check`, échec sur bug HIGH).
  - Trivy — scan CVE filesystem (CRITICAL = échec ; HIGH/MEDIUM = SARIF non bloquant).
  - Hadolint — lint du Dockerfile.
- Échec d'un gate = pipeline rouge, pas de déploiement.

### `deploy-staging.yml` — Déploiement staging automatique

- **Déclencheur :** fin réussie de `ci.yml` sur `main` (`workflow_run`).
- **Jobs :**
  1. Build de l'image Docker (multi-stage existant).
  2. Push sur `ghcr.io` avec deux tags : `sha-<short>` et `staging`.
  3. Déploiement SSH sur le VPS staging (environnement GitHub `staging`) :
     `docker compose -f docker-compose.staging.yml pull api && up -d --no-deps api`.
  4. Health check post-déploiement (`/api/v1/actuator/health`, 60 s max).

### `deploy-prod.yml` — Promotion en production

- **Déclencheur :** `workflow_dispatch` manuel.
- **Input :** `image_tag` (défaut = dernier `sha-<short>` déployé en staging).
- **Environnement GitHub `production`** : règle de protection exigeant une
  approbation manuelle avant exécution.
- **Jobs :**
  1. Vérification que le tag `image_tag` existe bien sur `ghcr.io`.
  2. Déploiement SSH sur le VPS prod : écriture de `DONY_IMAGE_TAG` dans le
     `.env` du serveur, puis `docker compose -f docker-compose.prod.yml pull api && up -d --no-deps api`.
  3. Health check post-déploiement.
  4. Création d'une release Sentry.
- **Principe clé :** aucun rebuild entre staging et prod. Le binaire promu en
  prod est **exactement** l'image testée en staging.

### `security-weekly.yml` — Scan de sécurité hebdomadaire

- **Déclencheur :** cron hebdomadaire + `workflow_dispatch`.
- OWASP Dependency-Check (échec sur CVSS ≥ 7), rapport HTML en artefact.

### Secrets CI

Stockés dans les **GitHub Environments** :
- `staging` : `STAGING_OVH_HOST`, `STAGING_OVH_USER`, `STAGING_OVH_SSH_KEY`.
- `production` : `PROD_OVH_HOST`, `PROD_OVH_USER`, `PROD_OVH_SSH_KEY`,
  `SENTRY_AUTH_TOKEN`, `SENTRY_ORG`, `SENTRY_PROJECT`.

## Composant 2 — Infrastructure des deux VPS

Chaque VPS dispose de son propre fichier Compose et de son `.env`
(jamais commité, présent uniquement sur le serveur).

| Fichier | Rôle |
|---|---|
| `docker-compose.staging.yml` | Stack staging : `api` + `db` + `nginx` + `db-backup` + `alloy` |
| `docker-compose.prod.yml` | Stack prod (existant, on ajoute le service `alloy`) |

Différences staging vs prod :

| Aspect | Staging | Prod |
|---|---|---|
| Sous-domaine | `api-staging.dony.app` | `api.dony.app` |
| Profil Spring | `staging` | `prod` |
| Base de données | `dony_staging` (isolée) | `dony_prod` |
| Clés Stripe | mode test | mode live |
| Tag image | `staging` | `DONY_IMAGE_TAG` (sha promu) |

Nouveau profil **`application-staging.yml`** : identique à `prod` pour le
durcissement (Swagger off, `forward-headers-strategy`), mais conserve des logs
plus verbeux (`com.dony.api: DEBUG`) pour faciliter le diagnostic.

## Composant 3 — Instrumentation du backend

### Dépendances

Ajout dans `pom.xml` : `io.micrometer:micrometer-registry-prometheus`.

### Configuration Actuator

- Endpoint `prometheus` ajouté à `management.endpoints.web.exposure.include`.
- Le port de management reste **8080** (inchangé) pour ne pas casser les
  health checks existants (`healthcheck` Docker et health check post-déploiement
  sur `/api/v1/actuator/health`).
- **Isolation publique assurée par nginx :** nginx est le seul point d'entrée
  public et ne route jamais `/api/v1/actuator/prometheus` vers l'extérieur
  (route bloquée explicitement). L'endpoint n'est donc accessible que depuis le
  réseau Docker interne `dony_internal`.
- Alloy, raccordé à `dony_internal`, scrape directement
  `api:8080/api/v1/actuator/prometheus` sans passer par nginx.

### Métriques techniques (out-of-the-box)

Fournies automatiquement par Micrometer/Actuator, aucun code métier :
JVM (heap, GC, threads), latence et throughput HTTP par endpoint, pool de
connexions HikariCP, cache Caffeine, métriques CPU/RAM de l'hôte (via Alloy).

### Métriques métier (instrumentation à écrire)

Un helper léger dans `common/metrics/` (conforme à la règle « code partagé dans
`common/`, jamais de `Utils.java` »), exposant des méthodes typées pour
incrémenter compteurs et enregistrer timers, en s'appuyant sur le
`MeterRegistry` injecté par Spring.

Instrumentation par package :

| Package | Métriques |
|---|---|
| `payments` | PaymentIntent créés / capturés / échoués, escrow libéré, remboursements |
| `matching` | Annonces créées, bids créés / acceptés / rejetés |
| `kyc` | Vérifications démarrées / réussies / échouées |
| `tracking` | Scans QR effectués, livraisons confirmées |
| `disputes` | Litiges ouverts / résolus |
| `notifications` | Notifications FCM et SMS envoyées / échouées |

Toutes les métriques métier sont nommées avec le préfixe `dony.` et portent
des tags cohérents (ex. `corridor`, `result`).

## Composant 4 — Agent Alloy & Grafana Cloud

### Grafana Alloy (un conteneur par VPS)

Service `alloy` ajouté à chaque fichier Compose, raccordé au réseau
`dony_internal`. Il collecte et pousse vers Grafana Cloud :

- **Métriques :** scrape de `api:8080/api/v1/actuator/prometheus` (réseau
  Docker interne) + métriques hôte (CPU, RAM, disque) via l'intégration
  `unix` d'Alloy.
- **Logs :** lecture des logs de tous les conteneurs (`api`, `nginx`, `db`)
  via l'API Docker, puis envoi vers Loki.

Toutes les données sont étiquetées `env=staging` ou `env=prod` pour
distinguer les deux serveurs dans une même instance Grafana.

Configuration Alloy versionnée dans le repo : `monitoring/alloy/config.alloy`.
Les identifiants Grafana Cloud (URLs de push, tokens) sont fournis via le
`.env` de chaque VPS.

### Grafana Cloud

- **Dashboards** (JSON versionnés dans `monitoring/dashboards/`) :
  - `dony-technique.json` — JVM, HTTP, HikariCP, cache, hôte.
  - `dony-metier.json` — paiements, bids, KYC, livraisons, litiges, notifications.
- **Règles d'alerte** (versionnées dans `monitoring/alerts/`) :
  - API indisponible (health KO ou absence de métriques).
  - Taux d'erreurs HTTP 5xx élevé.
  - Latence HTTP p95 élevée.
  - Heap JVM proche de la saturation.
  - Disque hôte > 85 %.
  - Pool de connexions HikariCP épuisé.
  - Taux d'échec de paiement anormal.
- **Contact point Discord :** webhook vers un salon `#alertes-prod` dédié.
- **Synthetic Monitoring :** sonde externe HTTP sur
  `https://api.dony.app/api/v1/actuator/health` — uptime indépendant de l'état
  des VPS.

## Sécurité

- L'endpoint `/actuator/prometheus` n'est jamais exposé publiquement : nginx
  bloque cette route, l'accès n'est possible que depuis le réseau Docker interne.
- Aucun secret commité : `.env` côté VPS, GitHub Environments côté CI.
- Les clés Stripe sont distinctes entre staging (test) et prod (live).
- Les tokens de push Grafana Cloud sont limités au scope d'écriture
  métriques/logs.

## Tests

Conformément au CLAUDE.md (couverture ≥ 90 %) :

- Tests unitaires du helper `common/metrics/`.
- Tests unitaires vérifiant que l'instrumentation métier incrémente bien les
  compteurs (mock du `MeterRegistry` ou `SimpleMeterRegistry`).
- Test d'intégration vérifiant que `/actuator/prometheus` répond et expose les
  métriques attendues.
- `./mvnw test` au vert avant tout commit.

## Documentation

- `monitoring/README.md` — guide de setup : compte Grafana Cloud, génération
  des tokens, création du webhook Discord, configuration des deux VPS.
- Document de story final dans `docs/stories-done/` décrivant l'implémentation
  backend.

## Actions manuelles requises de l'utilisateur

Ces étapes ne peuvent pas être automatisées et seront documentées dans un
guide pas-à-pas :

1. Créer le compte Grafana Cloud (free tier) et générer les tokens de push.
2. Provisionner le second VPS OVH (staging) et configurer son DNS
   (`api-staging.dony.app`).
3. Créer le salon Discord `#alertes-prod` et son webhook.
4. Renseigner les secrets dans les GitHub Environments `staging` et `production`.
5. Activer la règle de protection (approbation requise) sur l'environnement
   `production`.

## Séquence de construction proposée

1. Reconstruction des workflows CI/CD (`ci.yml`, `deploy-staging.yml`,
   `deploy-prod.yml`, `security-weekly.yml`).
2. Profil `application-staging.yml` + `docker-compose.staging.yml`.
3. Dépendance Micrometer Prometheus + configuration Actuator (port 9090).
4. Helper `common/metrics/` + instrumentation métier package par package.
5. Service Alloy dans les deux fichiers Compose + `monitoring/alloy/config.alloy`.
6. Dashboards, règles d'alerte, contact point Discord, Synthetic Monitoring.
7. Tests + documentation (`monitoring/README.md`, story `docs/stories-done/`).
