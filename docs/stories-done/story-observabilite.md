# Story CI/CD P2 — Observabilité production (Backend)

**Date:** 2026-05-17
**Status:** Complète

## Résumé

Mise en place de l'observabilité complète de l'API dony en production : exposition des métriques Spring Boot Actuator via Micrometer / Prometheus, instrumentation des 12 événements de domaine métier via un listener centralisé (`BusinessMetricsListener`), et collecte/expédition vers Grafana Cloud par l'agent Alloy déployé en sidecar Docker.

## Fichiers créés

- `src/main/java/com/dony/api/common/metrics/BusinessMetricsListener.java` — listener centralisé écoutant 12 événements de domaine Spring et incrémentant les compteurs Micrometer correspondants
- `src/test/java/com/dony/api/common/metrics/BusinessMetricsListenerTest.java` — 14 tests unitaires avec `SimpleMeterRegistry`
- `src/test/java/com/dony/api/common/metrics/PrometheusEndpointIntegrationTest.java` — test d'intégration vérifiant l'accessibilité de l'endpoint `/actuator/prometheus`
- `monitoring/alloy/config.alloy` — configuration de l'agent Grafana Alloy (scrape métriques, collecte logs Docker, envoi vers Grafana Cloud)
- `monitoring/README.md` — guide opérationnel complet : tokens, dashboards, alertes, uptime externe

## Fichiers modifiés

- `pom.xml` — ajout de la dépendance `micrometer-registry-prometheus` pour l'export au format Prometheus
- `src/main/resources/application.yml` — exposition de l'endpoint `prometheus` via `management.endpoints.web.exposure.include`
- `src/main/java/com/dony/api/config/SecurityConfig.java` — ajout de `/actuator/prometheus` en `permitAll` pour permettre le scrape par Alloy sans token Firebase
- `nginx/nginx.conf` — ajout du bloc `location /api/v1/actuator/ { return 404; }` pour bloquer l'accès externe aux endpoints Actuator (hors `/actuator/health` géré par un bloc dédié avant)
- `nginx/nginx.staging.conf` — même protection nginx côté staging
- `docker-compose.prod.yml` — ajout du service `alloy` (agent de collecte, montage `/host/root`, `/host/proc`, `/host/sys` pour les métriques hôte)
- `docker-compose.staging.yml` — même service `alloy` pour le staging

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

```
Événement de domaine Spring
        │
        ▼
BusinessMetricsListener (@EventListener)
        │  incrémente
        ▼
Compteur Micrometer (MeterRegistry)
        │  exposé par
        ▼
/actuator/prometheus (Spring Boot Actuator)
        │  scrape toutes les 30s
        ▼
Agent Grafana Alloy (conteneur Docker, réseau interne)
        │  remote_write
        ▼
Grafana Cloud (Prometheus)
        │
        ▼
Dashboards + Alertes Grafana
```

L'agent Alloy scrape également les métriques hôte (CPU, RAM, disque) via `prometheus.exporter.unix` et collecte les logs de tous les conteneurs Docker via le socket Unix, qu'il expédie vers Grafana Cloud Loki.

### Points d'entrée API

- `GET /api/v1/actuator/prometheus` — endpoint Prometheus, accessible uniquement depuis le réseau Docker interne (Alloy → api:8080). Bloqué par nginx pour le trafic externe. Aucun rôle requis (permit all), mais injoignable depuis Internet.
- `GET /api/v1/actuator/health` — endpoint de santé, public, géré par un bloc nginx dédié en amont du bloc de blocage général.

### Entités JPA impliquées

Aucune entité JPA n'est impliquée dans cette feature. `BusinessMetricsListener` est un composant Spring pur opérant sur la couche observabilité uniquement.

### Logique métier critique

`BusinessMetricsListener` ne contient aucune logique métier : il se contente d'écouter des événements déjà publiés par les services existants et d'incrémenter des compteurs. Cette séparation garantit que l'instrumentation est additive et non intrusive — aucun service métier n'a été modifié.

Pour `dony.bids.created`, le tag `corridor` (ex. `Paris-Dakar`) est extrait de l'événement. Si la valeur est nulle ou vide, la méthode `safe()` substitue la valeur `"unknown"` pour éviter une exception Micrometer sur un tag null.

Pour `dony.cancellations.confirmed`, le tag `reason` est extrait depuis l'enum de l'événement. S'il est null, `"unknown"` est utilisé par défaut.

### Mapping événement → compteur Micrometer

| Événement Spring | Compteur Micrometer | Nom Prometheus |
|---|---|---|
| `UserRegisteredEvent` | `dony.users.registered` | `dony_users_registered_total` |
| `AnnouncementCreatedEvent` | `dony.announcements.created` | `dony_announcements_created_total` |
| `BidCreatedEvent` | `dony.bids.created{corridor=...}` | `dony_bids_created_total` |
| `BidAcceptedEvent` | `dony.bids.accepted` | `dony_bids_accepted_total` |
| `BidRejectedEvent` | `dony.bids.rejected` | `dony_bids_rejected_total` |
| `PaymentEscrowReadyEvent` | `dony.payments.escrow_ready` | `dony_payments_escrow_ready_total` |
| `PaymentReleasedEvent` | `dony.payments.released` | `dony_payments_released_total` |
| `UserKycVerifiedEvent` | `dony.kyc.verified` | `dony_kyc_verified_total` |
| `DeliveryConfirmedEvent` | `dony.deliveries.confirmed` | `dony_deliveries_confirmed_total` |
| `DisputeOpenedEvent` | `dony.disputes.opened` | `dony_disputes_opened_total` |
| `CancellationConfirmedEvent` | `dony.cancellations.confirmed{reason=...}` | `dony_cancellations_confirmed_total` |
| `VoyageurNoShowEvent` | `dony.travelers.no_show` | `dony_travelers_no_show_total` |

### Events Spring publiés / écoutés

`BusinessMetricsListener` écoute (via `@EventListener`) les événements suivants, tous déjà publiés par les services existants :
- `UserRegisteredEvent` (package `auth/events`)
- `AnnouncementCreatedEvent`, `BidCreatedEvent`, `BidAcceptedEvent`, `BidRejectedEvent`, `VoyageurNoShowEvent` (package `matching/events`)
- `PaymentEscrowReadyEvent`, `PaymentReleasedEvent` (package `payments/events`)
- `UserKycVerifiedEvent` (package `kyc/events`)
- `DeliveryConfirmedEvent` (package `tracking/events`)
- `DisputeOpenedEvent` (package `disputes/events`)
- `CancellationConfirmedEvent` (package `cancellation/events`)

Aucun événement n'est publié par cette feature.

### Pièges et points d'attention

**1. Endpoint prometheus : permit all côté Spring, bloqué côté nginx**
`/actuator/prometheus` est configuré en `permitAll` dans `SecurityConfig` — c'est intentionnel et nécessaire pour que l'agent Alloy puisse scraper depuis le réseau Docker interne sans avoir à transmettre un token Firebase. La sécurité est assurée par nginx, qui renvoie un 404 pour toute requête sur `/api/v1/actuator/` venant de l'extérieur. Ne jamais retirer cette règle nginx sans vérifier que l'endpoint n'est pas exposé publiquement.

**2. Enregistrement paresseux des compteurs Micrometer**
Les séries `dony_*` n'apparaissent dans `/actuator/prometheus` (et donc dans Grafana) qu'après la première occurrence de l'événement correspondant. Sur un environnement vide ou de staging fraîchement déployé, certaines séries peuvent être absentes. C'est le comportement normal de Micrometer — les compteurs sont enregistrés au premier appel à `registry.counter(...).increment()`.

**3. @EventListener simple (pas @TransactionalEventListener)**
`BusinessMetricsListener` utilise `@EventListener` et non `@TransactionalEventListener(phase = AFTER_COMMIT)`. C'est délibéré : pour les métriques d'observabilité, un sur-comptage rare dû à un rollback de transaction est préférable à la complexité et au risque de perte de métriques sur erreur de transaction. Cette exception à la règle ne doit pas être copiée pour des listeners qui modifient des données métier.

**4. L'ordre des blocs nginx est crucial**
Dans `nginx.conf` et `nginx.staging.conf`, le bloc `location /api/v1/actuator/health` doit impérativement apparaître **avant** le bloc `location /api/v1/actuator/` (qui retourne 404). nginx utilise le principe du "premier match" pour les blocs `location` avec le même préfixe. Inverser l'ordre rendrait `/actuator/health` inaccessible.

**5. Variables d'environnement Alloy obligatoires**
Le service `alloy` dans les fichiers Docker Compose requiert 6 variables d'environnement : `GRAFANA_PROM_URL`, `GRAFANA_PROM_USER`, `GRAFANA_LOKI_URL`, `GRAFANA_LOKI_USER`, `GRAFANA_CLOUD_TOKEN`, `DONY_ENV`. Sans elles, le conteneur démarrera mais n'enverra aucune donnée vers Grafana Cloud. Vérifier avec `docker logs dony_alloy` après déploiement.

## Critères d'acceptation couverts

- [x] Les métriques Spring Boot (JVM, HTTP, pool DB) sont exportées au format Prometheus — implémenté via `micrometer-registry-prometheus` et l'exposition de l'endpoint `/actuator/prometheus`
- [x] 12 compteurs métier dony sont instrumentés — implémentés dans `BusinessMetricsListener` via `@EventListener` sur les événements de domaine existants
- [x] Les métriques sont collectées et envoyées vers Grafana Cloud — implémenté via l'agent Grafana Alloy (service Docker) avec `prometheus.remote_write`
- [x] Les logs des conteneurs sont collectés vers Grafana Cloud Loki — implémenté via `loki.source.docker` dans la configuration Alloy
- [x] Les métriques hôte (CPU, RAM, disque) sont disponibles — implémenté via `prometheus.exporter.unix` dans Alloy avec les volumes hôte montés
- [x] L'endpoint Prometheus n'est pas accessible publiquement — bloqué par nginx (`return 404` sur `/api/v1/actuator/`)
- [x] Des dashboards et alertes sont documentés — décrits dans `monitoring/README.md` (dashboard JVM ID 4701, dashboard métier avec requêtes PromQL, 7 règles d'alerte)

## Tests

- `./mvnw test` → tous les tests passent (0 rouge, 914 tests exécutés, 6 skipped)
- `./mvnw test jacoco:report` → couverture globale : **75%** (instructions)
- Tests ajoutés :
  - `src/test/java/com/dony/api/common/metrics/BusinessMetricsListenerTest.java` — 14 tests unitaires avec `SimpleMeterRegistry`, couvrant chaque handler de `BusinessMetricsListener` ainsi que les cas limites (corridor null, reason null)
  - `src/test/java/com/dony/api/common/metrics/PrometheusEndpointIntegrationTest.java` — test d'intégration `@SpringBootTest` + `MockMvc` vérifiant que `GET /actuator/prometheus` retourne HTTP 200 et un contenu text/plain

> Note : la couverture globale est de 75%, inférieure au seuil de 90% requis. Cette situation était pré-existante sur la branche avant l'ajout de cette feature — le code de métriques introduit ici est couvert à 100% par les tests ajoutés. Un effort de remontée de couverture globale sur les autres packages est à planifier séparément.

## Décisions techniques

**1. Listener centralisé dans `common/metrics/` plutôt que distribué dans chaque package**
Alternatives écartées : ajouter un appel `meterRegistry.counter(...).increment()` directement dans chaque service métier (BidService, PaymentService, etc.). Rejeté car cela introduirait un couplage avec la couche observabilité dans le code métier, rendrait les services moins testables, et violerait le principe de responsabilité unique. La solution retenue (`common/metrics/BusinessMetricsListener`) est purement additive et non intrusive.

**2. @EventListener simple plutôt que @TransactionalEventListener**
Alternatives écartées : `@TransactionalEventListener(phase = AFTER_COMMIT)`. Rejeté pour ce cas précis car les métriques d'observabilité tolèrent un sur-comptage rare (rollback) mieux qu'une sous-comptage ou une perte. La complexité supplémentaire (`REQUIRES_NEW`) n'apporte pas de valeur pour de l'instrumentation. Cette exception est documentée et ne doit pas être généralisée à des listeners métier.

**3. Scrape Prometheus toutes les 30 secondes**
Fréquence choisie comme compromis entre la fraîcheur des métriques et le volume de données transférées vers Grafana Cloud (facturation au volume sur le free tier). Sur des pics de trafic, la précision temporelle est de ±30s, ce qui est acceptable pour les dashboards métier.

**4. Agent Alloy plutôt que Prometheus autonome**
Alloy (successeur de Grafana Agent) est le seul agent officiellement supporté pour l'intégration native Grafana Cloud. Il combine scrape Prometheus, collecte Loki, et métriques hôte en un seul conteneur, réduisant la surface d'infrastructure sur le VPS.