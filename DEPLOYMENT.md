# Deployment Guide — dony-back Staging

Documentation du déploiement automatisé vers le VPS staging via GitHub Actions.

---

## Architecture du Déploiement

```
GitHub Repo (main, feature/*)
         ↓
    [CI Workflow] ← Tests, Build, Scan
         ↓
    [Deploy Workflow] ← Triggered on CI success
         ↓
    GitHub Secrets (environment: staging)
         ↓
    [Create .env.staging from secrets]
         ↓
    [Transfer via SSH to VPS]
         ↓
    [Docker Compose up -d]
         ↓
    VPS (141.95.41.96) — API Running
```

---

## Problèmes Résolus et Solutions

### 1. **PostgreSQL Authentication Failed**

**Problème :**
```
FATAL: password authentication failed for user "dony"
```

**Causes :**
- `SPRING_DATASOURCE_PASSWORD` hardcodé dans `docker-compose.staging.yml` ≠ `POSTGRES_PASSWORD` (venant du `.env.staging`)
- Base de données initialisée avec ancien mot de passe, en conflit avec nouveau

**Solution :**
```yaml
# Avant (docker-compose.staging.yml)
api:
  environment:
    SPRING_DATASOURCE_PASSWORD: oZDHFjZwSSmKWLQ+GtCpWsGSzhT+J2hrbG5fPvtHG3I=

db:
  environment:
    POSTGRES_PASSWORD: ${DB_PASSWORD}  # ≠ hardcoded value

# Après
api:
  environment:
    SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}  # Now consistent

db:
  environment:
    POSTGRES_PASSWORD: ${DB_PASSWORD}  # Same variable
```

**Impact :** Variables d'env cohérentes, pas de conflit au démarrage.

---

### 2. **Missing .env.staging File**

**Problème :**
```
docker-compose: "DB_PASSWORD" variable is not set. Defaulting to a blank string
```

**Causes :**
- Fichier `.env.staging` n'existait pas sur le VPS
- Docker Compose ne charge automatiquement que `.env`, pas `.env.staging`
- Workflow CI/CD ne créait pas ce fichier

**Solution :**

1. **Créer le fichier .env.staging.template** (commité dans le repo) :
   ```bash
   # Référence pour les variables requises
   DB_USERNAME=dony
   DB_PASSWORD=your_secure_password
   ENCRYPTION_KEY=base64_32_bytes_key
   ...
   ```

2. **Améliorer le workflow CI/CD** pour créer `.env.staging` dynamiquement depuis GitHub Secrets :
   ```yaml
   - name: Create .env.staging from secrets
     run: |
       cat > .env.staging << 'EOF'
       DB_USERNAME=${{ secrets.DB_USERNAME }}
       DB_PASSWORD=${{ secrets.DB_PASSWORD }}
       ...
       EOF
   ```

3. **Transférer via SCP** et utiliser `--env-file` explicitement :
   ```bash
   docker compose --env-file .env.staging -f docker-compose.staging.yml up -d
   ```

**Impact :** Secrets jamais commitées, chargés depuis GitHub Secrets au runtime.

---

### 3. **Private Repo — 404 on curl**

**Problème :**
```
curl -sSL https://raw.githubusercontent.com/MONDONY/dony-back/main/docker-compose.staging.yml
→ 404: Not Found (repo is private)
```

**Solution :**
Remplacer `curl` (qui ne peut pas accéder aux repos privés sans token) par **SCP direct** :
```bash
scp -i ~/.ssh/id_ed25519 docker-compose.staging.yml debian@141.95.41.96:~/dony/
```

**Impact :** Fichiers config transférés sans dépendre du token GitHub.

---

### 4. **Variable Loading in Docker Compose**

**Problème :**
```bash
docker compose -f docker-compose.staging.yml up -d
# Variables not loaded — uses defaults (empty strings)
```

**Cause :**
Docker Compose cherche `.env` par défaut, pas `.env.staging`.

**Solution :**
```bash
# Explicitement spécifier le fichier
docker compose --env-file .env.staging -f docker-compose.staging.yml up -d
```

**Impact :** Variables d'env correctement chargées du fichier `.env.staging`.

---

### 5. **Missing Required Environment Variables**

**Problème :**
```
Description: Binding to target com.dony.api.address.GooglePlacesProperties failed
Reason: must not be blank (app.places.apiKey)
```

**Cause :**
`GOOGLE_PLACES_API_KEY` vide ou non défini dans `.env.staging`.

**Solution :**
- Template `.env.staging` liste toutes les variables requises
- Pour le test : utiliser des placeholders valides
- Pour la production : remplir avec vraies valeurs depuis GitHub Secrets

---

## Setup Initial (One-time)

### Sur le VPS

```bash
ssh debian@141.95.41.96

# Créer le répertoire de déploiement
mkdir -p ~/dony
cd ~/dony

# Copier le fichier .env.staging.template en local et le remplir
# (L'utilisateur doit fournir les vraies valeurs)
```

### GitHub Secrets Setup

1. Aller à **Settings → Secrets and variables → Repository secrets**
2. Créer un nouvel **Environment** nommé `staging`
3. Ajouter les secrets pour cet environment (liste complète dans `.env.staging.template`)

---

## Workflow Deploiement Automatisé

### Déclenchement

```
Push vers main ou feature/* 
    → CI Workflow (tests, build, image push)
    → Deploy Workflow (on success, only main + feature/**)
    → Exécute:
       1. Create .env.staging from secrets
       2. Transfer configs via SCP
       3. Run docker compose
       4. Health check
```

### Fichiers workflow
- `.github/workflows/ci.yml` — Tests, build, push image
- `.github/workflows/deploy-staging.yml` — SSH, Docker Compose, health check

---

## Vérification Post-Déploiement

### Sur le VPS
```bash
ssh debian@141.95.41.96

cd ~/dony

# Vérifier les conteneurs
docker ps -a
# → dony_api, dony_db_staging doivent être UP

# Vérifier les logs
docker logs dony_api | tail -20
# → Doit voir "Started DonyBackApplication"

# Vérifier la BD
docker exec dony_db_staging pg_isready -U dony
# → accepting connections
```

### Health Check
```bash
# Depuis le VPS
wget -q -O - http://localhost:8080/api/v1/actuator/health

# Résultat attendu (JSON)
{"status":"UP","components":{"db":{"status":"UP"},...}}
```

---

## Troubleshooting

### API ne démarre pas
```bash
docker logs dony_api | tail -50
# → Chercher "ERROR" ou "FAILED TO START"
```

Causes courantes :
1. `ENCRYPTION_KEY` vide → Ajouter dans `.env.staging`
2. `GOOGLE_PLACES_API_KEY` vide → Ajouter placeholder ou vraie clé
3. Base de données pas ready → Attendre 10-15s et relancer

### BD ne démarre pas
```bash
docker logs dony_db_staging
```

Causes :
1. Conflit de conteneur → `docker container rm dony_db_staging` puis relancer
2. Conflit de volume → `docker volume rm dony_dony_db_staging_data` puis relancer
3. Permissions → Vérifier `~/dony` permissions (700+ pour user debian)

### Secrets non chargés
```bash
# Vérifier le fichier
cat ~/dony/.env.staging | grep DB_PASSWORD

# Vérifier que docker compose le charge
docker compose --env-file .env.staging config | grep POSTGRES_PASSWORD
```

---

## Notes de Sécurité

1. **Secrets jamais commités** → `.env.staging` dans `.gitignore`
2. **GitHub Secrets stockées chiffrées** → Jamais exposées dans les logs
3. **SSH key protégée** → `~/.ssh/id_ed25519` (permissions 600)
4. **Base données isolée** → Réseau Docker `dony_internal`, pas exposé

---

## Amélioration Future

- [ ] TLS/SSL pour nginx (Let's Encrypt)
- [ ] Monitoring/Alerting (Grafana complet)
- [ ] Backup automatisé vers S3
- [ ] Zero-downtime deployments (health check + rolling restart)
- [ ] Database migrations rollback strategy

