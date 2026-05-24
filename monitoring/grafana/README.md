# Dashboards Grafana — Dony Monitoring

4 dashboards préconfigurés pour monitorer l'API dony en staging.

## 📊 Dashboards disponibles

### 1. **Vue Globale** (`dashboard-overview.json`)
- État général du système
- Santé API et Infrastructure
- Métriques clés (RPS, erreurs, latence, CPU, RAM)
- Endpoints les plus utilisés
- Erreurs par endpoint

### 2. **API** (`dashboard-api.json`)
- Requêtes HTTP / sec
- Latence p99
- Taux d'erreurs 5xx
- Taux de succès
- Connexions pool BD
- Utilisation mémoire JVM

### 3. **Infrastructure** (`dashboard-infra.json`)
- Utilisation CPU
- Utilisation RAM
- Utilisation disque
- Charge système (1m, 5m, 15m)
- I/O disque
- Connexions réseau
- Uptime

### 4. **Database** (`dashboard-database.json`)
- Connexions BD (actives, idle, max)
- Timeouts pool
- Transactions / sec
- Slow queries (>500ms)
- Erreurs BD
- Taille DB
- Rows par table

---

## 🚀 Installation

### Option 1 : Importer manuellement (Simple)

1. Connecte-toi à **Grafana Cloud** : https://grafana.com/auth/sign-in/grafana-cloud
2. Va dans **Dashboards** → **+ New** → **Import**
3. Copie-colle le contenu de `dashboard-overview.json`
4. Clique sur **Load** → **Import**
5. Répète pour les 3 autres dashboards

### Option 2 : Import via API (Automatisé)

```bash
# Set your credentials
GRAFANA_URL="https://your-org.grafana.net"
API_TOKEN="your_api_token_from_grafana"

# Import tous les dashboards
for dashboard in monitoring/grafana/dashboard-*.json; do
  curl -X POST "$GRAFANA_URL/api/dashboards/db" \
    -H "Authorization: Bearer $API_TOKEN" \
    -H "Content-Type: application/json" \
    -d @"$dashboard"
  echo "Imported $(basename $dashboard)"
done
```

Pour générer un API token Grafana Cloud :
1. Va dans **Organization** → **API tokens**
2. Crée un token avec role **Editor**
3. Copie le token dans la commande

---

## 📈 Données collectées (via Alloy)

Les dashboards utilisent des métriques collectées par **Grafana Alloy** :

- **Métriques Prometheus** : Spring Boot Actuator + système hôte
- **Logs Loki** : Logs des containers Docker

Ces données sont envoyées à **Grafana Cloud** via les variables GitHub Secrets :
- `GRAFANA_PROM_URL` → endpoint Prometheus
- `GRAFANA_LOKI_URL` → endpoint Loki
- `GRAFANA_CLOUD_TOKEN` → authentification

---

## 🔧 Customisation

Chaque dashboard JSON peut être édité directement dans Grafana UI :
1. Va sur le dashboard
2. Clique **Edit**
3. Modifie les panneaux (titres, requêtes, seuils)
4. Sauvegarde

Ou modifie le JSON localement et réimporte.

---

## 📌 Alertes recommandées

Ajoute ces alertes dans Grafana :

```yaml
# API Down
expr: up{job="dony-api"} == 0
for: 1m

# High Error Rate
expr: sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) > 0.05

# High Latency p95
expr: histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) > 1

# High CPU
expr: 100 - avg(irate(node_cpu_seconds_total{mode="idle"}[5m])) * 100 > 80

# Disk Almost Full
expr: 100 - (node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"} * 100) > 85
```

---

## 🔍 Dépannage

### "No data" sur le dashboard
- Vérifier que **Alloy** est en UP : `docker ps | grep alloy`
- Vérifier que les secrets Grafana Cloud sont corrects
- Attendre 2-3 min que les données arrivent

### Requêtes Prometheus invalides
- Aller dans Grafana **Explore** → vérifier que Prometheus datasource répond
- Tester une requête simple : `up{job="dony-api"}`

### Logs manquants
- Vérifier que Loki datasource est configuré
- Vérifier que `GRAFANA_LOKI_URL` est correct
- Vérifier les logs Alloy : `docker logs dony_alloy`

---

## 📚 Ressources

- [Grafana Cloud Docs](https://grafana.com/docs/grafana-cloud/)
- [Prometheus Metrics](https://prometheus.io/docs/prometheus/latest/querying/basics/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)
