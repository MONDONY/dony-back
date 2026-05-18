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
