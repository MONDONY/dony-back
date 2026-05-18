# Configuration des variables d'environnement — dony-back

Ce guide liste **toutes** les variables et clés nécessaires, où les mettre, et comment les générer.

---

## Vue d'ensemble : deux endroits à configurer

```
GitHub (Settings → Secrets)       VPS ~/dony/.env
──────────────────────────         ───────────────────────────────
Secrets de déploiement SSH    +    Variables runtime de l'API
Tokens Sentry                      Clés Stripe, Firebase, S3, etc.
```

---

## 1. GitHub Secrets

Aller sur : **github.com/MONDONY/dony-back → Settings → Secrets and variables → Actions**

### 1.1 Secrets partagés (tous les environnements)

| Secret | Description | Comment l'obtenir |
|--------|-------------|-------------------|
| `OVH_HOST` | IP publique du VPS | Dashboard OVH → Serveur → IP |
| `OVH_USER` | Utilisateur SSH sur le VPS | Généralement `ubuntu` ou `debian` |
| `OVH_SSH_KEY` | Clé SSH privée pour se connecter au VPS | Voir §3 ci-dessous |

> **Note :** Si staging et prod sont sur deux VPS différents, créer ces 3 secrets dans chaque **GitHub Environment** (staging / production) plutôt qu'en global.

### 1.2 Secrets pour Sentry (déploiement prod uniquement)

| Secret | Description | Comment l'obtenir |
|--------|-------------|-------------------|
| `SENTRY_AUTH_TOKEN` | Token d'auth Sentry | sentry.io → Settings → Auth Tokens → Create |
| `SENTRY_ORG` | Slug de ton organisation Sentry | URL Sentry : `sentry.io/organizations/<slug>/` |
| `SENTRY_PROJECT` | Nom du projet Sentry | Sentry → Projects → nom du projet `dony-back` |

### 1.3 Créer les GitHub Environments

Dans **Settings → Environments** :
- Créer `staging` — pas de règle de protection (déploiement automatique)
- Créer `production` — ajouter une **Required reviewer** (toi-même) pour forcer une validation manuelle avant chaque déploiement prod

---

## 2. VPS Staging — fichier `~/dony/.env`

Se connecter au VPS staging, puis :

```bash
cd ~/dony
nano .env
```

Contenu complet du fichier `.env` staging :

```dotenv
# ── Base de données ──────────────────────────────────────────────
DB_USERNAME=dony
DB_PASSWORD=<mot_de_passe_fort>

# ── Stripe ───────────────────────────────────────────────────────
STRIPE_SECRET_KEY=sk_test_...
STRIPE_WEBHOOK_SECRET=whsec_...

# ── Sentry ───────────────────────────────────────────────────────
SENTRY_DSN=https://...@sentry.io/...

# ── Chiffrement KYC (AES-256) ────────────────────────────────────
ENCRYPTION_KEY=<clé_base64_32_bytes>

# ── Google Places ────────────────────────────────────────────────
GOOGLE_PLACES_API_KEY=AIza...

# ── Hetzner Object Storage (S3-compatible) ───────────────────────
AWS_S3_ENDPOINT=https://fsn1.your-objectstorage.com
AWS_S3_REGION=fsn1
AWS_S3_BUCKET=dony-staging
AWS_S3_ACCESS_KEY=<access_key>
AWS_S3_SECRET_KEY=<secret_key>

# ── CORS ─────────────────────────────────────────────────────────
CORS_ALLOWED_ORIGINS=https://staging.yadony.com

# ── Grafana Cloud ────────────────────────────────────────────────
GRAFANA_PROM_URL=https://prometheus-prod-XX-prod-XX.grafana.net/api/prom/push
GRAFANA_PROM_USER=123456
GRAFANA_LOKI_URL=https://logs-prod-XXX.grafana.net/loki/api/v1/push
GRAFANA_LOKI_USER=789012
GRAFANA_CLOUD_TOKEN=glc_eyJ...
```

---

## 3. VPS Production — fichier `~/dony/.env`

Même structure que staging, avec :
- Clés Stripe **live** (`sk_live_...` / `whsec_...`)
- Bucket S3 prod (`dony-prod`)
- `CORS_ALLOWED_ORIGINS=https://yadony.com`
- Variable gérée automatiquement par le workflow deploy-prod :

```dotenv
# Géré automatiquement par GitHub Actions deploy-prod.yml — ne pas modifier manuellement
DONY_IMAGE_TAG=staging
```

---

## 4. Fichier Firebase — sur les deux VPS

Le fichier `firebase-service-account.json` doit être placé manuellement sur chaque VPS :

```bash
# Depuis ta machine locale, copier le fichier sur le VPS
scp firebase-service-account.json ubuntu@<IP_VPS>:~/dony/firebase-service-account.json
```

**Comment l'obtenir :**
1. console.firebase.google.com → Projet dony
2. Paramètres du projet → Comptes de service
3. Générer une nouvelle clé privée → télécharger le JSON

> Ne jamais committer ce fichier dans git. Il est listé dans `.gitignore`.

---

## 5. Comment générer les clés

### Clé SSH pour GitHub Actions → VPS

Sur ta machine locale :

```bash
# Générer une paire de clés dédiée au déploiement (sans passphrase)
ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/dony_deploy -N ""

# Afficher la clé publique → à ajouter sur le VPS
cat ~/.ssh/dony_deploy.pub

# Afficher la clé privée → à coller dans GitHub Secret OVH_SSH_KEY
cat ~/.ssh/dony_deploy
```

Sur le VPS :

```bash
# Ajouter la clé publique aux clés autorisées
echo "<contenu_de_dony_deploy.pub>" >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

### Clé ENCRYPTION\_KEY (AES-256)

```bash
# Génère une clé aléatoire de 32 bytes encodée en base64
openssl rand -base64 32
```

> Utiliser la **même clé** en staging et prod si tu veux pouvoir lire les données KYC des deux environnements. Si les bases de données sont complètement séparées, des clés différentes sont acceptables.

### Mot de passe base de données

```bash
openssl rand -base64 24
```

---

## 6. Où trouver chaque clé externe

| Service | Où trouver |
|---------|-----------|
| **Stripe** `sk_test_` / `sk_live_` | dashboard.stripe.com → Développeurs → Clés API |
| **Stripe** `whsec_` | dashboard.stripe.com → Développeurs → Webhooks → ton endpoint → Signing secret |
| **Sentry DSN** | sentry.io → Projet → Settings → SDK Setup → DSN |
| **Google Places** | console.cloud.google.com → APIs & Services → Credentials |
| **Hetzner S3** | console.hetzner.cloud → Object Storage → Bucket → Access Keys |
| **Grafana Cloud** | grafana.com → My Account → Stack → Prometheus / Loki → details + token |

---

## 7. Checklist de mise en route

### GitHub
- [ ] Secrets `OVH_HOST`, `OVH_USER`, `OVH_SSH_KEY` créés
- [ ] Secrets `SENTRY_AUTH_TOKEN`, `SENTRY_ORG`, `SENTRY_PROJECT` créés
- [ ] Environment `staging` créé (sans protection)
- [ ] Environment `production` créé (avec required reviewer)

### VPS Staging
- [ ] `~/dony/.env` créé avec toutes les variables
- [ ] `~/dony/firebase-service-account.json` copié
- [ ] Clé SSH publique ajoutée dans `~/.ssh/authorized_keys`
- [ ] Dossier `~/dony/backups/` créé : `mkdir -p ~/dony/backups`
- [ ] Dossier `~/dony/nginx/certs/` créé pour Let's Encrypt

### VPS Production
- [ ] Même checklist que staging
- [ ] Clés Stripe **live** (pas test)
- [ ] `DONY_IMAGE_TAG=staging` présent dans `.env` (valeur initiale)

### Vérification finale
```bash
# Sur le VPS, tester que Docker Compose lit bien le .env
cd ~/dony
docker compose -f docker-compose.staging.yml config | grep -E "DB_USERNAME|STRIPE"
# Les valeurs réelles doivent apparaître (pas les placeholders ${...})
```