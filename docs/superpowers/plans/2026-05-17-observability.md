# Observabilité production (Grafana Cloud) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Doter le backend dony d'une observabilité de production complète : métriques techniques et métier exposées au format Prometheus, collectées par un agent Grafana Alloy sur chaque VPS et poussées vers Grafana Cloud (dashboards, alertes Discord, monitoring uptime externe).

**Architecture:** Le backend expose `/actuator/prometheus` via Micrometer (métriques techniques out-of-the-box). Les métriques métier sont produites par un unique `BusinessMetricsListener` qui écoute les événements de domaine déjà publiés par l'application — aucun service métier n'est modifié, conformément à la règle « communication cross-package par événements uniquement ». Un conteneur Grafana Alloy par VPS scrape les métriques, collecte les logs Docker et pousse le tout vers Grafana Cloud, où sont configurés dashboards, alertes et sondes uptime.

**Tech Stack:** Spring Boot Actuator, Micrometer (`micrometer-registry-prometheus`), Spring Application Events, Grafana Alloy, Grafana Cloud (Prometheus + Loki + Alerting + Synthetic Monitoring), Docker Compose.

---

## Prérequis

- **Le plan `2026-05-17-cicd-pipeline.md` doit être exécuté avant celui-ci** :
  ce plan modifie `docker-compose.staging.yml` et `nginx/nginx.staging.conf`,
  créés par le plan CI/CD.
- Actions manuelles de l'utilisateur (hors plan, nécessaires au runtime) :
  1. Créer un compte **Grafana Cloud** (free tier) et une stack.
  2. Générer un **token d'accès** Grafana Cloud avec les scopes
     `metrics:write` et `logs:write`. Noter aussi les URLs et identifiants de
     push Prometheus et Loki (visibles dans « Connections → … »).
  3. Créer un salon Discord `#alertes-prod` et un **webhook** dessus.
  4. Ajouter dans le `.env` de **chaque VPS** : `DONY_ENV` (`staging` ou
     `prod`), `GRAFANA_PROM_URL`, `GRAFANA_PROM_USER`, `GRAFANA_LOKI_URL`,
     `GRAFANA_LOKI_USER`, `GRAFANA_CLOUD_TOKEN`.

## Métriques métier — mapping événement → compteur

L'instrumentation s'appuie exclusivement sur les événements de domaine
existants (vérifiés dans le code) :

| Compteur Micrometer | Événement écouté | Tag |
|---|---|---|
| `dony.users.registered` | `UserRegisteredEvent` | — |
| `dony.announcements.created` | `AnnouncementCreatedEvent` | — |
| `dony.bids.created` | `BidCreatedEvent` | `corridor` |
| `dony.bids.accepted` | `BidAcceptedEvent` | — |
| `dony.bids.rejected` | `BidRejectedEvent` | — |
| `dony.payments.escrow_ready` | `PaymentEscrowReadyEvent` | — |
| `dony.payments.released` | `PaymentReleasedEvent` | — |
| `dony.kyc.verified` | `UserKycVerifiedEvent` | — |
| `dony.deliveries.confirmed` | `DeliveryConfirmedEvent` | — |
| `dony.disputes.opened` | `DisputeOpenedEvent` | — |
| `dony.cancellations.confirmed` | `CancellationConfirmedEvent` | `reason` |
| `dony.travelers.no_show` | `VoyageurNoShowEvent` | — |

> **Décision de design (raffinement du spec) :** le spec prévoyait un *helper*
> de métriques + une instrumentation par package. Le code étant déjà riche en
> événements de domaine, on fusionne le tout dans un unique
> `BusinessMetricsListener` event-driven (YAGNI : pas de wrapper à un seul
> consommateur). Les compteurs sont enregistrés paresseusement (à la première
> occurrence) — comportement standard de Micrometer pour les `Counter`.

## File Structure

| Fichier | Responsabilité |
|---|---|
| `pom.xml` (modifier) | Ajout de la dépendance `micrometer-registry-prometheus` |
| `src/main/resources/application.yml` (modifier) | Exposer l'endpoint Actuator `prometheus` |
| `src/main/java/com/dony/api/config/SecurityConfig.java` (modifier) | `permitAll` sur `/actuator/prometheus` |
| `src/main/java/com/dony/api/common/metrics/BusinessMetricsListener.java` (créer) | Compteurs métier event-driven |
| `src/test/java/com/dony/api/common/metrics/BusinessMetricsListenerTest.java` (créer) | Tests unitaires des compteurs |
| `src/test/java/com/dony/api/common/metrics/PrometheusEndpointIntegrationTest.java` (créer) | Test d'intégration de l'endpoint |
| `nginx/nginx.conf` (modifier) | Bloquer `/actuator/*` (sauf health) côté public |
| `nginx/nginx.staging.conf` (modifier) | Idem pour staging |
| `monitoring/alloy/config.alloy` (créer) | Config de l'agent Grafana Alloy |
| `docker-compose.prod.yml` (modifier) | Ajout du service `alloy` |
| `docker-compose.staging.yml` (modifier) | Ajout du service `alloy` |
| `monitoring/README.md` (créer) | Setup Grafana Cloud, dashboards (PromQL), alertes, uptime |
| `docs/stories-done/story-observabilite.md` (créer) | Documentation finale backend |

---

### Task 1: Ajouter la dépendance Micrometer Prometheus

**Files:**
- Modify: `pom.xml` (section `<dependencies>`)

- [ ] **Step 1: Ajouter la dépendance**

Juste après le bloc de la dépendance Sentry (`sentry-spring-boot-starter-jakarta`),
ajouter :

```xml
		<!-- Micrometer — export des métriques au format Prometheus -->
		<dependency>
			<groupId>io.micrometer</groupId>
			<artifactId>micrometer-registry-prometheus</artifactId>
		</dependency>
```

La version est gérée par le BOM `spring-boot-starter-parent` — ne pas la fixer.

- [ ] **Step 2: Vérifier que la dépendance est résolue**

Run: `./mvnw -q dependency:resolve 2>&1 | tail -3 && ./mvnw -q dependency:tree -Dincludes=io.micrometer:micrometer-registry-prometheus 2>&1 | grep micrometer-registry-prometheus`
Expected: une ligne contenant `io.micrometer:micrometer-registry-prometheus:jar:...`

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "Feat: dépendance Micrometer Prometheus"
```

---

### Task 2: Exposer et sécuriser l'endpoint `/actuator/prometheus`

**Files:**
- Test: `src/test/java/com/dony/api/common/metrics/PrometheusEndpointIntegrationTest.java`
- Modify: `src/main/resources/application.yml` (bloc `management.endpoints.web.exposure.include`)
- Modify: `src/main/java/com/dony/api/config/SecurityConfig.java` (liste `permitAll`)

- [ ] **Step 1: Écrire le test d'intégration (qui échoue)**

```java
package com.dony.api.common.metrics;

import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PrometheusEndpointIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockBean  private FirebaseAuth firebaseAuth;

    @Test
    void prometheusEndpoint_isPublic_andExposesJvmMetrics() throws Exception {
        mockMvc.perform(get("/api/v1/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(content().string(
                org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")));
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./mvnw test -Dtest=PrometheusEndpointIntegrationTest -Dspring.profiles.active=test`
Expected: FAIL — l'endpoint renvoie 404 (non exposé) ou 401 (non autorisé)

- [ ] **Step 3: Exposer l'endpoint dans `application.yml`**

Dans le bloc `management`, remplacer :

```yaml
      exposure:
        include: health,info,metrics
```

par :

```yaml
      exposure:
        include: health,info,metrics,prometheus
```

- [ ] **Step 4: Autoriser l'endpoint dans `SecurityConfig`**

Dans la liste `requestMatchers(...)` du bloc `permitAll()`, ajouter la ligne
`"/actuator/prometheus",` juste après `"/actuator/info",` :

```java
                    "/auth/**",
                    "/actuator/health",
                    "/actuator/info",
                    "/actuator/prometheus",
                    "/config/**",
```

> L'endpoint devient accessible sans token Firebase pour que l'agent Alloy
> puisse le scraper. Il n'est jamais exposé publiquement : nginx le bloque
> (Task 3), seul le réseau Docker interne y accède.

- [ ] **Step 5: Lancer le test pour vérifier qu'il passe**

Run: `./mvnw test -Dtest=PrometheusEndpointIntegrationTest -Dspring.profiles.active=test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/application.yml src/main/java/com/dony/api/config/SecurityConfig.java src/test/java/com/dony/api/common/metrics/PrometheusEndpointIntegrationTest.java
git commit -m "Feat: exposer l'endpoint Actuator prometheus"
```

---

### Task 3: Bloquer `/actuator/*` côté public dans nginx

**Files:**
- Modify: `nginx/nginx.conf`
- Modify: `nginx/nginx.staging.conf`

- [ ] **Step 1: Ajouter la règle de blocage dans `nginx/nginx.conf`**

Juste après le bloc `location /api/v1/actuator/health { ... }`, ajouter :

```nginx
        # ── Actuator : seul /health est public, le reste est bloqué ─────────
        location /api/v1/actuator/ {
            return 404;
        }
```

nginx applique la correspondance de préfixe la plus longue : une requête vers
`/api/v1/actuator/health` continue de matcher son bloc dédié, tandis que
`/api/v1/actuator/prometheus` (et `/metrics`, `/info`…) tombe sur ce `return 404`.

- [ ] **Step 2: Appliquer la même règle dans `nginx/nginx.staging.conf`**

Ajouter le même bloc, juste après le `location /api/v1/actuator/health` du
fichier staging.

- [ ] **Step 3: Valider la syntaxe des deux fichiers**

Run: `for f in nginx/nginx.conf nginx/nginx.staging.conf; do docker run --rm -v "$(pwd)/$f:/etc/nginx/nginx.conf:ro" nginx:1.27-alpine nginx -t -c /etc/nginx/nginx.conf 2>&1 | grep -E "syntax is ok" && echo "$f OK"; done`
Expected: `nginx/nginx.conf OK` puis `nginx/nginx.staging.conf OK`

- [ ] **Step 4: Commit**

```bash
git add nginx/nginx.conf nginx/nginx.staging.conf
git commit -m "Feat: bloquer les endpoints Actuator non-health côté public"
```

---

### Task 4: `BusinessMetricsListener` — compteurs métier

**Files:**
- Test: `src/test/java/com/dony/api/common/metrics/BusinessMetricsListenerTest.java`
- Create: `src/main/java/com/dony/api/common/metrics/BusinessMetricsListener.java`

- [ ] **Step 1: Écrire les tests unitaires (qui échouent)**

```java
package com.dony.api.common.metrics;

import com.dony.api.auth.events.UserRegisteredEvent;
import com.dony.api.cancellation.CancellationReason;
import com.dony.api.cancellation.events.CancellationConfirmedEvent;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.dony.api.matching.events.BidCreatedEvent;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessMetricsListenerTest {

    private SimpleMeterRegistry registry;
    private BusinessMetricsListener listener;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        listener = new BusinessMetricsListener(registry);
    }

    @Test
    void onUserRegistered_incrementsCounter() {
        listener.onUserRegistered(new UserRegisteredEvent(UUID.randomUUID(), "fb-uid"));

        assertThat(registry.counter("dony.users.registered").count()).isEqualTo(1.0);
    }

    @Test
    void onBidCreated_incrementsCounterWithCorridorTag() {
        listener.onBidCreated(new BidCreatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Awa", new BigDecimal("5.0"), "PAR-DKR"));

        assertThat(registry.counter("dony.bids.created", "corridor", "PAR-DKR").count())
                .isEqualTo(1.0);
    }

    @Test
    void onBidCreated_nullCorridor_usesUnknownTag() {
        listener.onBidCreated(new BidCreatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Awa", new BigDecimal("5.0"), null));

        assertThat(registry.counter("dony.bids.created", "corridor", "unknown").count())
                .isEqualTo(1.0);
    }

    @Test
    void onDeliveryConfirmed_incrementsCounter() {
        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        assertThat(registry.counter("dony.deliveries.confirmed").count()).isEqualTo(1.0);
    }

    @Test
    void onCancellationConfirmed_usesReasonTag() {
        listener.onCancellationConfirmed(new CancellationConfirmedEvent(
                UUID.randomUUID(), UUID.randomUUID(), CancellationReason.TRIP_CANCELLED));

        assertThat(registry.counter("dony.cancellations.confirmed",
                "reason", "TRIP_CANCELLED").count()).isEqualTo(1.0);
    }

    @Test
    void onBidAccepted_multipleEvents_accumulate() {
        listener.onBidAccepted(new BidAcceptedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        listener.onBidAccepted(new BidAcceptedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        assertThat(registry.counter("dony.bids.accepted").count()).isEqualTo(2.0);
    }
}
```

- [ ] **Step 2: Lancer les tests pour vérifier qu'ils échouent**

Run: `./mvnw test -Dtest=BusinessMetricsListenerTest -Dspring.profiles.active=test`
Expected: FAIL — `BusinessMetricsListener` n'existe pas (erreur de compilation)

- [ ] **Step 3: Créer `BusinessMetricsListener`**

```java
package com.dony.api.common.metrics;

import com.dony.api.auth.events.UserRegisteredEvent;
import com.dony.api.cancellation.events.CancellationConfirmedEvent;
import com.dony.api.disputes.events.DisputeOpenedEvent;
import com.dony.api.kyc.events.UserKycVerifiedEvent;
import com.dony.api.matching.events.AnnouncementCreatedEvent;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.dony.api.matching.events.BidCreatedEvent;
import com.dony.api.matching.events.BidRejectedEvent;
import com.dony.api.matching.events.VoyageurNoShowEvent;
import com.dony.api.payments.events.PaymentEscrowReadyEvent;
import com.dony.api.payments.events.PaymentReleasedEvent;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Instrumentation métier centralisée. Écoute les événements de domaine déjà
 * publiés par l'application et incrémente des compteurs Micrometer. Aucun
 * service métier n'est modifié — la communication reste event-driven,
 * conformément à la règle d'architecture « pas d'injection cross-package ».
 *
 * Les compteurs sont enregistrés paresseusement par Micrometer : une série
 * n'apparaît dans /actuator/prometheus qu'après sa première occurrence.
 */
@Component
public class BusinessMetricsListener {

    private final MeterRegistry registry;

    public BusinessMetricsListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @EventListener
    public void onUserRegistered(UserRegisteredEvent event) {
        registry.counter("dony.users.registered").increment();
    }

    @EventListener
    public void onAnnouncementCreated(AnnouncementCreatedEvent event) {
        registry.counter("dony.announcements.created").increment();
    }

    @EventListener
    public void onBidCreated(BidCreatedEvent event) {
        registry.counter("dony.bids.created", "corridor", safe(event.getCorridor()))
                .increment();
    }

    @EventListener
    public void onBidAccepted(BidAcceptedEvent event) {
        registry.counter("dony.bids.accepted").increment();
    }

    @EventListener
    public void onBidRejected(BidRejectedEvent event) {
        registry.counter("dony.bids.rejected").increment();
    }

    @EventListener
    public void onPaymentEscrowReady(PaymentEscrowReadyEvent event) {
        registry.counter("dony.payments.escrow_ready").increment();
    }

    @EventListener
    public void onPaymentReleased(PaymentReleasedEvent event) {
        registry.counter("dony.payments.released").increment();
    }

    @EventListener
    public void onKycVerified(UserKycVerifiedEvent event) {
        registry.counter("dony.kyc.verified").increment();
    }

    @EventListener
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        registry.counter("dony.deliveries.confirmed").increment();
    }

    @EventListener
    public void onDisputeOpened(DisputeOpenedEvent event) {
        registry.counter("dony.disputes.opened").increment();
    }

    @EventListener
    public void onCancellationConfirmed(CancellationConfirmedEvent event) {
        String reason = event.reason() == null ? "unknown" : event.reason().name();
        registry.counter("dony.cancellations.confirmed", "reason", reason).increment();
    }

    @EventListener
    public void onTravelerNoShow(VoyageurNoShowEvent event) {
        registry.counter("dony.travelers.no_show").increment();
    }

    private static String safe(String value) {
        return (value == null || value.isBlank()) ? "unknown" : value;
    }
}
```

- [ ] **Step 4: Lancer les tests pour vérifier qu'ils passent**

Run: `./mvnw test -Dtest=BusinessMetricsListenerTest -Dspring.profiles.active=test`
Expected: PASS (6 tests)

- [ ] **Step 5: Lancer toute la suite de tests**

Run: `./mvnw test -Dspring.profiles.active=test`
Expected: BUILD SUCCESS — aucun test cassé

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/dony/api/common/metrics/BusinessMetricsListener.java src/test/java/com/dony/api/common/metrics/BusinessMetricsListenerTest.java
git commit -m "Feat: compteurs métier event-driven (BusinessMetricsListener)"
```

---

### Task 5: Configuration de l'agent Grafana Alloy

**Files:**
- Create: `monitoring/alloy/config.alloy`

- [ ] **Step 1: Créer la config Alloy**

Cette config scrape les métriques de l'API et de l'hôte, collecte les logs des
conteneurs Docker, et pousse le tout vers Grafana Cloud. Les identifiants sont
fournis par variables d'environnement (depuis le `.env` du VPS). Le label
`env` distingue staging de prod.

```alloy
// ── Sortie métriques : Grafana Cloud (Prometheus) ────────────────────────
prometheus.remote_write "grafana_cloud" {
  external_labels = {
    env = sys.env("DONY_ENV"),
  }
  endpoint {
    url = sys.env("GRAFANA_PROM_URL")
    basic_auth {
      username = sys.env("GRAFANA_PROM_USER")
      password = sys.env("GRAFANA_CLOUD_TOKEN")
    }
  }
}

// ── Scrape des métriques applicatives (Spring Boot Actuator) ──────────────
prometheus.scrape "dony_api" {
  targets = [
    {
      "__address__"      = "api:8080",
      "__metrics_path__" = "/api/v1/actuator/prometheus",
    },
  ]
  forward_to      = [prometheus.remote_write.grafana_cloud.receiver]
  scrape_interval = "30s"
  job_name        = "dony-api"
}

// ── Scrape des métriques de l'hôte (CPU, RAM, disque) ─────────────────────
// Les chemins pointent vers les systèmes de fichiers de l'hôte montés
// dans le conteneur (voir les volumes du service `alloy`).
prometheus.exporter.unix "host" {
  rootfs_path = "/host/root"
  procfs_path = "/host/proc"
  sysfs_path  = "/host/sys"
}

prometheus.scrape "host" {
  targets         = prometheus.exporter.unix.host.targets
  forward_to      = [prometheus.remote_write.grafana_cloud.receiver]
  scrape_interval = "30s"
  job_name        = "host"
}

// ── Sortie logs : Grafana Cloud (Loki) ───────────────────────────────────
loki.write "grafana_cloud" {
  endpoint {
    url = sys.env("GRAFANA_LOKI_URL")
    basic_auth {
      username = sys.env("GRAFANA_LOKI_USER")
      password = sys.env("GRAFANA_CLOUD_TOKEN")
    }
  }
}

// ── Découverte et collecte des logs des conteneurs Docker ────────────────
discovery.docker "containers" {
  host = "unix:///var/run/docker.sock"
}

discovery.relabel "docker_logs" {
  targets = discovery.docker.containers.targets

  rule {
    source_labels = ["__meta_docker_container_name"]
    regex         = "/(.*)"
    target_label  = "container"
  }
}

loki.source.docker "containers" {
  host       = "unix:///var/run/docker.sock"
  targets    = discovery.relabel.docker_logs.output
  forward_to = [loki.process.add_env_label.receiver]
}

loki.process "add_env_label" {
  stage.static_labels {
    values = {
      env = sys.env("DONY_ENV"),
    }
  }
  forward_to = [loki.write.grafana_cloud.receiver]
}
```

- [ ] **Step 2: Valider la syntaxe de la config Alloy**

Run: `docker run --rm -v "$(pwd)/monitoring/alloy:/cfg:ro" grafana/alloy:latest fmt /cfg/config.alloy > /dev/null && echo "ALLOY OK"`
Expected: `ALLOY OK` (la commande `fmt` échoue si la syntaxe est invalide)

- [ ] **Step 3: Commit**

```bash
git add monitoring/alloy/config.alloy
git commit -m "Feat: configuration de l'agent Grafana Alloy"
```

---

### Task 6: Ajouter le service Alloy aux fichiers Compose

**Files:**
- Modify: `docker-compose.prod.yml`
- Modify: `docker-compose.staging.yml`

- [ ] **Step 1: Ajouter le service `alloy` dans `docker-compose.prod.yml`**

Ajouter ce service dans la section `services:` (par exemple après `api`) :

```yaml
  alloy:
    image: grafana/alloy:latest
    container_name: dony_alloy
    restart: unless-stopped
    command:
      - run
      - /etc/alloy/config.alloy
      - --storage.path=/var/lib/alloy/data
    environment:
      DONY_ENV: prod
      GRAFANA_PROM_URL: ${GRAFANA_PROM_URL}
      GRAFANA_PROM_USER: ${GRAFANA_PROM_USER}
      GRAFANA_LOKI_URL: ${GRAFANA_LOKI_URL}
      GRAFANA_LOKI_USER: ${GRAFANA_LOKI_USER}
      GRAFANA_CLOUD_TOKEN: ${GRAFANA_CLOUD_TOKEN}
    volumes:
      - ./monitoring/alloy/config.alloy:/etc/alloy/config.alloy:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - dony_alloy_data:/var/lib/alloy/data
      # Systèmes de fichiers de l'hôte pour les métriques CPU/RAM/disque
      - /proc:/host/proc:ro
      - /sys:/host/sys:ro
      - /:/host/root:ro,rslave
    networks:
      - dony_internal
    depends_on:
      - api
```

Et déclarer le volume — dans la section `volumes:` de `docker-compose.prod.yml`,
ajouter `dony_alloy_data:` sous le volume existant `dony_db_prod_data:` :

```yaml
volumes:
  dony_db_prod_data:
  dony_alloy_data:
```

- [ ] **Step 2: Ajouter le service `alloy` dans `docker-compose.staging.yml`**

Même service, mais avec `DONY_ENV: staging` :

```yaml
  alloy:
    image: grafana/alloy:latest
    container_name: dony_alloy
    restart: unless-stopped
    command:
      - run
      - /etc/alloy/config.alloy
      - --storage.path=/var/lib/alloy/data
    environment:
      DONY_ENV: staging
      GRAFANA_PROM_URL: ${GRAFANA_PROM_URL}
      GRAFANA_PROM_USER: ${GRAFANA_PROM_USER}
      GRAFANA_LOKI_URL: ${GRAFANA_LOKI_URL}
      GRAFANA_LOKI_USER: ${GRAFANA_LOKI_USER}
      GRAFANA_CLOUD_TOKEN: ${GRAFANA_CLOUD_TOKEN}
    volumes:
      - ./monitoring/alloy/config.alloy:/etc/alloy/config.alloy:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - dony_alloy_data:/var/lib/alloy/data
      # Systèmes de fichiers de l'hôte pour les métriques CPU/RAM/disque
      - /proc:/host/proc:ro
      - /sys:/host/sys:ro
      - /:/host/root:ro,rslave
    networks:
      - dony_internal
    depends_on:
      - api
```

Et dans la section `volumes:` de `docker-compose.staging.yml` :

```yaml
volumes:
  dony_db_staging_data:
  dony_alloy_data:
```

- [ ] **Step 3: Valider les deux fichiers Compose**

Run: `for e in prod staging; do DB_USERNAME=x DB_PASSWORD=x docker compose -f docker-compose.$e.yml config -q && echo "$e OK"; done`
Expected: `prod OK` puis `staging OK`

- [ ] **Step 4: Commit**

```bash
git add docker-compose.prod.yml docker-compose.staging.yml
git commit -m "Feat: agent Alloy dans les stacks staging et prod"
```

---

### Task 7: Documentation monitoring (Grafana Cloud, dashboards, alertes)

**Files:**
- Create: `monitoring/README.md`

- [ ] **Step 1: Créer `monitoring/README.md`**

Ce document décrit la configuration côté Grafana Cloud : ce qui se fait dans
l'interface web et ne peut pas être versionné en code à ce stade. Les requêtes
PromQL exactes sont fournies — ce ne sont pas des placeholders.

````markdown
# Monitoring dony — Grafana Cloud

## 1. Compte et tokens

1. Créer un compte sur https://grafana.com (free tier) et une stack.
2. Dans « Connections → Add new connection → Hosted Prometheus metrics »,
   récupérer : URL de push (`GRAFANA_PROM_URL`), username (`GRAFANA_PROM_USER`).
3. Idem pour « Hosted logs (Loki) » : `GRAFANA_LOKI_URL`, `GRAFANA_LOKI_USER`.
4. Générer un token d'accès (`GRAFANA_CLOUD_TOKEN`) avec les scopes
   `metrics:write` et `logs:write`.
5. Renseigner ces 5 variables + `DONY_ENV` dans le `.env` de chaque VPS.

## 2. Agent Alloy

L'agent tourne en conteneur sur chaque VPS (service `alloy` des fichiers
Compose). Vérifier après déploiement : `docker logs dony_alloy` ne doit pas
afficher d'erreur d'authentification, et les cibles doivent être `up` dans
Grafana (Explore → `up{job="dony-api"}`).

## 3. Dashboard technique

Importer le dashboard communautaire **JVM (Micrometer)** : Grafana →
Dashboards → New → Import → ID `4701`. Source de données : le Prometheus de
la stack. Il couvre heap, GC, threads, et les requêtes HTTP.

## 4. Dashboard métier

Créer un nouveau dashboard « dony — Métier » avec un panneau par métrique.
Type de panneau : « Time series », sauf mention contraire. Requêtes PromQL
(le filtre `{env="$env"}` suppose une variable de dashboard `env`) :

| Panneau | Requête PromQL |
|---|---|
| Inscriptions / h | `sum(rate(dony_users_registered_total{env="$env"}[1h])) * 3600` |
| Annonces créées / h | `sum(rate(dony_announcements_created_total{env="$env"}[1h])) * 3600` |
| Bids créés / h par corridor | `sum by (corridor) (rate(dony_bids_created_total{env="$env"}[1h])) * 3600` |
| Taux d'acceptation des bids | `sum(rate(dony_bids_accepted_total{env="$env"}[6h])) / clamp_min(sum(rate(dony_bids_created_total{env="$env"}[6h])), 0.0001)` |
| Paiements en escrow / h | `sum(rate(dony_payments_escrow_ready_total{env="$env"}[1h])) * 3600` |
| Paiements libérés / h | `sum(rate(dony_payments_released_total{env="$env"}[1h])) * 3600` |
| KYC validés / h | `sum(rate(dony_kyc_verified_total{env="$env"}[1h])) * 3600` |
| Livraisons confirmées / h | `sum(rate(dony_deliveries_confirmed_total{env="$env"}[1h])) * 3600` |
| Litiges ouverts (total 24 h) | `sum(increase(dony_disputes_opened_total{env="$env"}[24h]))` |
| Annulations par motif (24 h) | `sum by (reason) (increase(dony_cancellations_confirmed_total{env="$env"}[24h]))` |
| No-show voyageurs (24 h) | `sum(increase(dony_travelers_no_show_total{env="$env"}[24h]))` |

> Les séries `dony_*` n'apparaissent qu'après la première occurrence de
> l'événement correspondant. C'est normal sur un environnement neuf.

Après création, exporter le JSON (Dashboard settings → JSON Model) et le
committer dans `monitoring/dashboards/dony-metier.json`.

## 5. Contact point Discord

Grafana → Alerting → Contact points → Add :
- Type : Discord.
- Webhook URL : celle du salon `#alertes-prod`.
- Tester avec « Test ».

## 6. Règles d'alerte

Grafana → Alerting → Alert rules → New. Pour chaque règle : source de données
Prometheus, condition `IS ABOVE`/`IS BELOW` selon le seuil, `for` = durée de
persistance, contact point = Discord.

| Alerte | Requête PromQL | Condition | for |
|---|---|---|---|
| API prod down | `up{job="dony-api", env="prod"}` | `IS BELOW 1` | 2m |
| Taux d'erreurs 5xx élevé | `sum(rate(http_server_requests_seconds_count{env="prod", status=~"5.."}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count{env="prod"}[5m])), 0.0001)` | `IS ABOVE 0.05` | 5m |
| Latence p95 élevée | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{env="prod"}[5m])))` | `IS ABOVE 2` | 10m |
| Heap JVM proche saturation | `sum(jvm_memory_used_bytes{env="prod", area="heap"}) / sum(jvm_memory_max_bytes{env="prod", area="heap"})` | `IS ABOVE 0.9` | 10m |
| Disque hôte presque plein | `1 - (node_filesystem_avail_bytes{env="prod", fstype!~"tmpfs|overlay"} / node_filesystem_size_bytes{env="prod", fstype!~"tmpfs|overlay"})` | `IS ABOVE 0.85` | 15m |
| Pool DB épuisé | `hikaricp_connections_pending{env="prod"}` | `IS ABOVE 5` | 5m |
| Échecs de paiement anormaux | `sum(increase(dony_disputes_opened_total{env="prod"}[1h]))` | `IS ABOVE 10` | 5m |

## 7. Monitoring uptime externe

Grafana → Testing & synthetics → Synthetic Monitoring → Create check :
- Type : HTTP.
- Cible : `https://api.dony.app/api/v1/actuator/health`.
- Fréquence : 1 min. Sondes : 2-3 régions proches (Europe).
- Associer une alerte sur l'échec de la sonde → contact point Discord.
````

- [ ] **Step 2: Vérifier la création du fichier**

Run: `test -f monitoring/README.md && echo OK`
Expected: `OK`

- [ ] **Step 3: Commit**

```bash
git add monitoring/README.md
git commit -m "Docs: guide monitoring Grafana Cloud (dashboards, alertes, uptime)"
```

---

### Task 8: Documentation de story

**Files:**
- Create: `docs/stories-done/story-observabilite.md`

- [ ] **Step 1: Lancer la suite de tests complète avec couverture**

Run: `./mvnw test jacoco:report -Dspring.profiles.active=test`
Expected: BUILD SUCCESS — noter le pourcentage de couverture global dans `target/site/jacoco/index.html`

- [ ] **Step 2: Créer le document de story**

Rédiger `docs/stories-done/story-observabilite.md` en suivant le gabarit
imposé par `dony-back/CLAUDE.md` (sections : Résumé, Fichiers créés, Fichiers
modifiés, Comment ça fonctionne, Critères d'acceptation, Tests, Décisions
techniques). Points obligatoires à documenter :

- Le flux : événement de domaine → `BusinessMetricsListener` → compteur
  Micrometer → `/actuator/prometheus` → Alloy → Grafana Cloud.
- Le mapping événement → compteur (table de l'en-tête de ce plan).
- Le piège : `/actuator/prometheus` est en `permitAll` côté Spring mais bloqué
  par nginx — ne jamais l'exposer publiquement.
- Le pourcentage de couverture JaCoCo réel constaté à l'étape 1.

- [ ] **Step 3: Commit**

```bash
git add docs/stories-done/story-observabilite.md
git commit -m "Docs: story observabilité production"
```

---

## Vérification finale

- [ ] **Tous les tests passent**

Run: `./mvnw test -Dspring.profiles.active=test`
Expected: BUILD SUCCESS

- [ ] **L'endpoint Prometheus expose les métriques métier après un événement**

Démarrage local (`./mvnw spring-boot:run -Dspring.profiles.active=dev`), puis
déclencher une inscription ou un bid, et vérifier :

Run: `curl -s http://localhost:8080/api/v1/actuator/prometheus | grep -E "^dony_|jvm_memory_used_bytes"`
Expected: au moins `jvm_memory_used_bytes` et une ligne `dony_..._total`

- [ ] **Les fichiers Compose et la config Alloy sont valides**

Run: `DB_USERNAME=x DB_PASSWORD=x docker compose -f docker-compose.prod.yml config -q && docker run --rm -v "$(pwd)/monitoring/alloy:/cfg:ro" grafana/alloy:latest fmt /cfg/config.alloy > /dev/null && echo "INFRA OK"`
Expected: `INFRA OK`

---

## Notes pour l'exécutant

- Les tâches 1 à 4 sont du code Java testable (TDD strict). Les tâches 5 à 8
  sont de la configuration et de la documentation : la « vérification » se
  fait par linters (`alloy fmt`, `docker compose config`) et inspection.
- Le `BusinessMetricsListener` utilise `@EventListener` synchrone (pas
  `@TransactionalEventListener`) : un compteur peut, très rarement,
  sur-compter si la transaction émettrice est annulée après publication.
  C'est acceptable pour des métriques d'ops — la simplicité prime.
- La couverture JaCoCo doit rester ≥ 90 % (règle `CLAUDE.md`). Le nouveau code
  est entièrement couvert par `BusinessMetricsListenerTest` et
  `PrometheusEndpointIntegrationTest`.
- Le montage de `/var/run/docker.sock` dans Alloy est en lecture seule et sert
  uniquement à découvrir/lire les logs des conteneurs — comportement standard
  d'un agent de collecte.
