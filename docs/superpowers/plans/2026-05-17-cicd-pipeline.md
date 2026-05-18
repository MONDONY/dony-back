# Pipeline CI/CD (staging + prod) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reconstruire la chaîne CI/CD du backend dony : quality gates sur chaque PR, déploiement automatique sur le VPS staging à chaque merge sur `main`, et promotion manuelle contrôlée vers le VPS prod.

**Architecture:** Quatre workflows GitHub Actions remplacent les trois actuels. La CI valide la qualité ; `deploy-staging.yml` build l'image Docker, la pousse sur `ghcr.io` et la déploie sur staging ; `deploy-prod.yml` (manuel, avec approbation via GitHub Environment) promeut en prod **exactement** l'image validée en staging — sans rebuild. Chaque VPS a son propre fichier Compose, son profil Spring et son fichier `.env`.

**Tech Stack:** GitHub Actions, Docker, Docker Compose, GitHub Container Registry (`ghcr.io`), nginx, Spring Boot profiles, SSH (`appleboy/ssh-action`).

---

## Prérequis (actions manuelles hors plan)

Ces étapes sont à réaliser par l'utilisateur ; elles ne bloquent pas l'écriture
des fichiers mais sont nécessaires pour que les déploiements fonctionnent :

1. Provisionner le VPS staging OVH, installer Docker + Docker Compose.
2. Configurer le DNS `api-staging.dony.app` → IP du VPS staging.
3. Créer les **GitHub Environments** `staging` et `production` (Settings →
   Environments). Activer sur `production` la règle « Required reviewers ».
4. Renseigner les secrets, **scopés par environnement** (mêmes noms dans les
   deux environnements) :
   - Environnement `staging` : `OVH_HOST`, `OVH_USER`, `OVH_SSH_KEY`.
   - Environnement `production` : `OVH_HOST`, `OVH_USER`, `OVH_SSH_KEY`,
     `SENTRY_AUTH_TOKEN`, `SENTRY_ORG`, `SENTRY_PROJECT`.
5. Sur chaque VPS, créer le répertoire `~/dony` contenant : le fichier Compose
   correspondant, le `.env`, `firebase-service-account.json`, et le dossier
   `nginx/`.

> **Note d'implémentation :** ce plan utilise des secrets scopés par
> environnement (noms identiques `OVH_HOST` etc. dans `staging` et
> `production`) — c'est l'usage idiomatique de GitHub. Cela raffine le spec qui
> mentionnait des préfixes `STAGING_`/`PROD_`.

## File Structure

| Fichier | Responsabilité |
|---|---|
| `src/main/resources/application-staging.yml` (créer) | Profil Spring staging : durcissement comme prod, logs verbeux |
| `nginx/nginx.staging.conf` (créer) | Config nginx staging : `server_name api-staging.dony.app` |
| `docker-compose.staging.yml` (créer) | Stack staging : api + db + nginx + db-backup |
| `docker-compose.prod.yml` (modifier) | Tag d'image paramétrable via `DONY_IMAGE_TAG` |
| `.github/workflows/ci.yml` (créer) | Quality gates — remplace `quality.yml` |
| `.github/workflows/deploy-staging.yml` (créer) | Build + push + déploiement staging auto |
| `.github/workflows/deploy-prod.yml` (créer) | Promotion manuelle vers prod |
| `.github/workflows/security-weekly.yml` (créer) | Scan OWASP hebdo — remplace `owasp-weekly.yml` |
| `.github/workflows/quality.yml` (supprimer) | Remplacé par `ci.yml` |
| `.github/workflows/deploy.yml` (supprimer) | Remplacé par `deploy-staging.yml` + `deploy-prod.yml` |
| `.github/workflows/owasp-weekly.yml` (supprimer) | Remplacé par `security-weekly.yml` |
| `docs/deployment/cicd-setup.md` (créer) | Guide de configuration des deux VPS et des Environments |

> **Note sur la vérification :** les fichiers de ce plan sont du YAML de
> configuration, non du code Java — le TDD classique ne s'applique pas. La
> vérification de chaque tâche se fait via des linters (`actionlint`,
> `docker compose config`) qui valident la syntaxe et la cohérence.

---

### Task 1: Profil Spring `staging`

**Files:**
- Create: `src/main/resources/application-staging.yml`

- [ ] **Step 1: Créer le fichier de profil staging**

Le profil staging reprend le durcissement de `application-prod.yml` (Swagger
désactivé, gestion des headers proxy) mais conserve des logs `DEBUG` sur le
package applicatif pour faciliter le diagnostic avant la prod.

```yaml
# Staging profile — copie durcie de prod avec logs verbeux pour diagnostic
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false

server:
  forward-headers-strategy: framework

logging:
  level:
    com.dony.api: DEBUG
    org.springframework.security: INFO
```

- [ ] **Step 2: Vérifier la validité YAML**

Run: `python3 -c "import yaml; yaml.safe_load(open('src/main/resources/application-staging.yml'))" && echo OK`
Expected: `OK`

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application-staging.yml
git commit -m "Feat: profil Spring staging"
```

---

### Task 2: Config nginx staging

**Files:**
- Create: `nginx/nginx.staging.conf`

- [ ] **Step 1: Créer la config nginx staging**

Identique à `nginx/nginx.conf` mais avec le `server_name` et les chemins de
certificats du sous-domaine staging.

```nginx
worker_processes auto;
error_log /var/log/nginx/error.log warn;

events {
    worker_connections 1024;
}

http {
    include       /etc/nginx/mime.types;
    default_type  application/octet-stream;

    log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                    '$status $body_bytes_sent "$http_referer" '
                    '"$http_user_agent"';

    access_log /var/log/nginx/access.log main;

    sendfile        on;
    keepalive_timeout 65;
    server_tokens   off;

    limit_req_zone $binary_remote_addr zone=api_general:10m rate=30r/m;
    limit_req_zone $binary_remote_addr zone=api_sensitive:10m rate=5r/m;

    server {
        listen 80;
        server_name api-staging.dony.app;

        location /.well-known/acme-challenge/ {
            root /var/www/certbot;
        }

        location / {
            return 301 https://$host$request_uri;
        }
    }

    server {
        listen 443 ssl;
        http2  on;
        server_name api-staging.dony.app;

        ssl_certificate     /etc/letsencrypt/live/api-staging.dony.app/fullchain.pem;
        ssl_certificate_key /etc/letsencrypt/live/api-staging.dony.app/privkey.pem;
        ssl_protocols       TLSv1.2 TLSv1.3;
        ssl_ciphers         ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384;
        ssl_prefer_server_ciphers off;
        ssl_session_cache   shared:SSL:10m;
        ssl_session_timeout 1d;

        add_header Strict-Transport-Security "max-age=63072000; includeSubDomains" always;
        add_header X-Frame-Options DENY always;
        add_header X-Content-Type-Options nosniff always;
        add_header Referrer-Policy no-referrer always;

        client_max_body_size 15M;

        resolver 127.0.0.11 valid=10s ipv6=off;
        set $backend http://api:8080;

        location ~ ^/api/v1/(auth|kyc) {
            limit_req zone=api_sensitive burst=3 nodelay;
            limit_req_status 429;
            proxy_pass         $backend;
            proxy_set_header   Host              $host;
            proxy_set_header   X-Real-IP         $remote_addr;
            proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
            proxy_set_header   X-Forwarded-Proto $scheme;
            proxy_read_timeout 30s;
        }

        location /api/v1/ {
            limit_req zone=api_general burst=10 nodelay;
            limit_req_status 429;
            proxy_pass         $backend;
            proxy_set_header   Host              $host;
            proxy_set_header   X-Real-IP         $remote_addr;
            proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
            proxy_set_header   X-Forwarded-Proto $scheme;
            proxy_read_timeout 30s;
        }

        location /api/v1/actuator/health {
            proxy_pass $backend;
            proxy_set_header Host $host;
        }

        location / {
            return 404;
        }
    }
}
```

- [ ] **Step 2: Valider la syntaxe nginx**

Run: `docker run --rm -v "$(pwd)/nginx/nginx.staging.conf:/etc/nginx/nginx.conf:ro" nginx:1.27-alpine nginx -t -c /etc/nginx/nginx.conf 2>&1 | grep -E "syntax is ok|test is successful" && echo VALIDE`
Expected: `VALIDE` (la résolution des certificats échoue hors VPS, mais la syntaxe est validée)

- [ ] **Step 3: Commit**

```bash
git add nginx/nginx.staging.conf
git commit -m "Feat: config nginx staging (api-staging.dony.app)"
```

---

### Task 3: Fichier Compose staging

**Files:**
- Create: `docker-compose.staging.yml`

- [ ] **Step 1: Créer `docker-compose.staging.yml`**

Calqué sur `docker-compose.prod.yml` : base de données `dony_staging`,
profil Spring `staging`, image taguée `staging`, config nginx staging.

```yaml
services:
  api:
    image: ghcr.io/mondony/dony-back:staging
    container_name: dony_api
    restart: unless-stopped
    environment:
      SPRING_PROFILES_ACTIVE: staging
      DB_USERNAME: ${DB_USERNAME}
      DB_PASSWORD: ${DB_PASSWORD}
      STRIPE_SECRET_KEY: ${STRIPE_SECRET_KEY}
      STRIPE_WEBHOOK_SECRET: ${STRIPE_WEBHOOK_SECRET}
      SENTRY_DSN: ${SENTRY_DSN}
      ENCRYPTION_KEY: ${ENCRYPTION_KEY}
      GOOGLE_PLACES_API_KEY: ${GOOGLE_PLACES_API_KEY}
      AWS_S3_ENDPOINT: ${AWS_S3_ENDPOINT}
      AWS_S3_REGION: ${AWS_S3_REGION}
      AWS_S3_BUCKET: ${AWS_S3_BUCKET}
      AWS_S3_ACCESS_KEY: ${AWS_S3_ACCESS_KEY}
      AWS_S3_SECRET_KEY: ${AWS_S3_SECRET_KEY}
      APP_BASE_URL: https://api-staging.dony.app
      CORS_ALLOWED_ORIGINS: ${CORS_ALLOWED_ORIGINS}
    volumes:
      - ./firebase-service-account.json:/app/firebase-service-account.json:ro
    depends_on:
      db:
        condition: service_healthy
    networks:
      - dony_internal
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/api/v1/actuator/health || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s

  db:
    image: postgres:16-alpine
    container_name: dony_db_staging
    restart: unless-stopped
    environment:
      POSTGRES_DB: dony_staging
      POSTGRES_USER: ${DB_USERNAME}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - dony_db_staging_data:/var/lib/postgresql/data
    networks:
      - dony_internal
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USERNAME} -d dony_staging"]
      interval: 10s
      timeout: 5s
      retries: 10

  nginx:
    image: nginx:1.27-alpine
    container_name: dony_nginx
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/nginx.staging.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/certs:/etc/letsencrypt:ro
      - ./nginx/www:/var/www/certbot:ro
    depends_on:
      - api
    networks:
      - dony_internal

volumes:
  dony_db_staging_data:

networks:
  dony_internal:
    driver: bridge
```

- [ ] **Step 2: Valider le fichier Compose**

Run: `DB_USERNAME=x DB_PASSWORD=x docker compose -f docker-compose.staging.yml config -q && echo VALIDE`
Expected: `VALIDE`

- [ ] **Step 3: Commit**

```bash
git add docker-compose.staging.yml
git commit -m "Feat: stack Docker Compose staging"
```

---

### Task 4: Tag d'image paramétrable en prod

**Files:**
- Modify: `docker-compose.prod.yml:2` (ligne `image:` du service `api`)

- [ ] **Step 1: Rendre le tag d'image configurable**

Remplacer la ligne :

```yaml
    image: ghcr.io/mondony/dony-back:latest
```

par :

```yaml
    image: ghcr.io/mondony/dony-back:${DONY_IMAGE_TAG:-latest}
```

Cela permet à `deploy-prod.yml` de promouvoir un tag immuable précis
(`sha-xxxxxxx`) en écrivant `DONY_IMAGE_TAG` dans le `.env` du VPS — et donc de
faire un rollback en redéployant un ancien `sha`.

- [ ] **Step 2: Valider le fichier Compose**

Run: `DB_USERNAME=x DB_PASSWORD=x docker compose -f docker-compose.prod.yml config -q && echo VALIDE`
Expected: `VALIDE`

- [ ] **Step 3: Commit**

```bash
git add docker-compose.prod.yml
git commit -m "Feat: tag d'image prod paramétrable via DONY_IMAGE_TAG"
```

---

### Task 5: Workflow CI — Quality Gates

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Créer `ci.yml`**

Reprend les 4 gates de l'actuel `quality.yml`. Le `name:` est fixé à `CI` —
c'est ce nom exact que `deploy-staging.yml` référencera dans son `workflow_run`.

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

env:
  JAVA_VERSION: '21'

permissions:
  contents: read
  security-events: write

jobs:

  test:
    name: Tests & Couverture
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: temurin
          cache: maven
      - name: Tests unitaires (gate bloquant)
        run: |
          chmod +x mvnw
          ./mvnw test -Dspring.profiles.active=test --no-transfer-progress
      - name: Rapport JaCoCo (non bloquant)
        run: ./mvnw jacoco:prepare-agent test jacoco:report -Dspring.profiles.active=test --no-transfer-progress
        continue-on-error: true
      - name: Upload rapport couverture
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: jacoco-report
          path: target/site/jacoco/
          retention-days: 7

  static-analysis:
    name: SpotBugs
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: temurin
          cache: maven
      - name: Compilation complète (sources + tests)
        run: |
          chmod +x mvnw
          ./mvnw test-compile --no-transfer-progress
      - name: SpotBugs — bugs HIGH = échec
        run: ./mvnw spotbugs:check --no-transfer-progress
      - name: Upload rapport SpotBugs
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: spotbugs-report
          path: target/spotbugsXml.xml
          retention-days: 7

  security-scan:
    name: Trivy CVE Scan
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Trivy — CVE CRITICAL = échec
        uses: aquasecurity/trivy-action@v0.35.0
        with:
          scan-type: fs
          scan-ref: .
          severity: CRITICAL
          exit-code: '1'
          ignore-unfixed: true
          format: table
          skip-dirs: target,docs,.github,.mvn
      - name: Trivy — rapport SARIF (non bloquant)
        uses: aquasecurity/trivy-action@v0.35.0
        if: always()
        with:
          scan-type: fs
          scan-ref: .
          severity: HIGH,MEDIUM
          exit-code: '0'
          ignore-unfixed: true
          format: sarif
          output: trivy-results.sarif
          skip-dirs: target,docs,.github,.mvn
      - name: Upload SARIF vers GitHub Security
        uses: github/codeql-action/upload-sarif@v3
        if: always()
        with:
          sarif_file: trivy-results.sarif

  docker-lint:
    name: Hadolint Dockerfile
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: hadolint/hadolint-action@v3.1.0
        with:
          dockerfile: Dockerfile
          failure-threshold: warning
```

- [ ] **Step 2: Valider le workflow avec actionlint**

Run: `docker run --rm -v "$(pwd):/repo" --workdir /repo rhysd/actionlint:latest -color .github/workflows/ci.yml && echo VALIDE`
Expected: `VALIDE` (aucune erreur de syntaxe)

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "Feat: workflow CI quality gates"
```

---

### Task 6: Workflow de déploiement staging

**Files:**
- Create: `.github/workflows/deploy-staging.yml`

- [ ] **Step 1: Créer `deploy-staging.yml`**

Déclenché après la réussite du workflow `CI` sur `main`. Build l'image, la
pousse sur `ghcr.io` (tags `sha-<court>` + `staging`), puis déploie sur le VPS
staging via SSH.

```yaml
name: Deploy Staging

on:
  workflow_run:
    workflows: ["CI"]
    types: [completed]
    branches: [main]

env:
  REGISTRY: ghcr.io
  IMAGE_NAME: ghcr.io/mondony/dony-back

jobs:

  check-ci:
    name: Vérification CI
    runs-on: ubuntu-latest
    steps:
      - name: La CI a-t-elle réussi ?
        run: |
          if [ "${{ github.event.workflow_run.conclusion }}" != "success" ]; then
            echo "CI échouée — déploiement bloqué"
            exit 1
          fi
          echo "CI validée — déploiement autorisé"

  build-push:
    name: Build & Push image
    runs-on: ubuntu-latest
    needs: check-ci
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ github.event.workflow_run.head_sha }}
      - name: Login ghcr.io
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - name: Métadonnées image
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.IMAGE_NAME }}
          tags: |
            type=sha,prefix=sha-,format=short,enable=true
            type=raw,value=staging
      - name: Build & Push
        uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}

  deploy:
    name: Déploiement staging
    runs-on: ubuntu-latest
    needs: build-push
    environment: staging
    steps:
      - name: Déploiement via SSH
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.OVH_HOST }}
          username: ${{ secrets.OVH_USER }}
          key: ${{ secrets.OVH_SSH_KEY }}
          script: |
            cd ~/dony
            echo "${{ secrets.GITHUB_TOKEN }}" | docker login ghcr.io -u ${{ github.actor }} --password-stdin
            docker compose -f docker-compose.staging.yml pull api
            docker compose -f docker-compose.staging.yml up -d --no-deps api
            docker image prune -f
      - name: Health check post-déploiement (60s max)
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.OVH_HOST }}
          username: ${{ secrets.OVH_USER }}
          key: ${{ secrets.OVH_SSH_KEY }}
          script: |
            for i in $(seq 1 12); do
              STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/actuator/health)
              if [ "$STATUS" = "200" ]; then
                echo "API staging healthy (tentative $i)"
                exit 0
              fi
              echo "Tentative $i — HTTP $STATUS — attente 5s..."
              sleep 5
            done
            echo "API staging non disponible après 60s"
            exit 1
```

- [ ] **Step 2: Valider le workflow avec actionlint**

Run: `docker run --rm -v "$(pwd):/repo" --workdir /repo rhysd/actionlint:latest -color .github/workflows/deploy-staging.yml && echo VALIDE`
Expected: `VALIDE`

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/deploy-staging.yml
git commit -m "Feat: workflow déploiement staging automatique"
```

---

### Task 7: Workflow de promotion en production

**Files:**
- Create: `.github/workflows/deploy-prod.yml`

- [ ] **Step 1: Créer `deploy-prod.yml`**

Déclenché **manuellement**. L'input `image_tag` permet de choisir l'image à
promouvoir (défaut `staging` = dernière image déployée en staging ; pour un
rollback, passer un `sha-xxxxxxx` précis). L'environnement `production` impose
une approbation manuelle avant exécution.

```yaml
name: Deploy Production

on:
  workflow_dispatch:
    inputs:
      image_tag:
        description: "Tag de l'image à promouvoir (ex: staging ou sha-abc1234)"
        required: true
        default: staging

env:
  REGISTRY: ghcr.io
  IMAGE_NAME: ghcr.io/mondony/dony-back

jobs:

  verify-image:
    name: Vérification de l'image
    runs-on: ubuntu-latest
    steps:
      - name: Login ghcr.io
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - name: L'image existe-t-elle sur ghcr.io ?
        run: |
          if ! docker manifest inspect ${{ env.IMAGE_NAME }}:${{ inputs.image_tag }} > /dev/null 2>&1; then
            echo "Image ${{ env.IMAGE_NAME }}:${{ inputs.image_tag }} introuvable sur ghcr.io"
            exit 1
          fi
          echo "Image trouvée — promotion autorisée"

  deploy:
    name: Déploiement production
    runs-on: ubuntu-latest
    needs: verify-image
    environment: production
    steps:
      - name: Déploiement via SSH
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.OVH_HOST }}
          username: ${{ secrets.OVH_USER }}
          key: ${{ secrets.OVH_SSH_KEY }}
          script: |
            cd ~/dony
            grep -v '^DONY_IMAGE_TAG=' .env > .env.tmp || true
            echo "DONY_IMAGE_TAG=${{ inputs.image_tag }}" >> .env.tmp
            mv .env.tmp .env
            echo "${{ secrets.GITHUB_TOKEN }}" | docker login ghcr.io -u ${{ github.actor }} --password-stdin
            docker compose -f docker-compose.prod.yml pull api
            docker compose -f docker-compose.prod.yml up -d --no-deps api
            docker image prune -f
      - name: Health check post-déploiement (60s max)
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.OVH_HOST }}
          username: ${{ secrets.OVH_USER }}
          key: ${{ secrets.OVH_SSH_KEY }}
          script: |
            for i in $(seq 1 12); do
              STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/actuator/health)
              if [ "$STATUS" = "200" ]; then
                echo "API prod healthy (tentative $i)"
                exit 0
              fi
              echo "Tentative $i — HTTP $STATUS — attente 5s..."
              sleep 5
            done
            echo "API prod non disponible après 60s"
            exit 1
      - name: Release Sentry
        uses: getsentry/action-release@v3
        if: success()
        env:
          SENTRY_AUTH_TOKEN: ${{ secrets.SENTRY_AUTH_TOKEN }}
          SENTRY_ORG: ${{ secrets.SENTRY_ORG }}
          SENTRY_PROJECT: ${{ secrets.SENTRY_PROJECT }}
        with:
          environment: production
```

- [ ] **Step 2: Valider le workflow avec actionlint**

Run: `docker run --rm -v "$(pwd):/repo" --workdir /repo rhysd/actionlint:latest -color .github/workflows/deploy-prod.yml && echo VALIDE`
Expected: `VALIDE`

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/deploy-prod.yml
git commit -m "Feat: workflow promotion manuelle en production"
```

---

### Task 8: Workflow sécurité hebdo + suppression des anciens workflows

**Files:**
- Create: `.github/workflows/security-weekly.yml`
- Delete: `.github/workflows/quality.yml`, `.github/workflows/deploy.yml`, `.github/workflows/owasp-weekly.yml`

- [ ] **Step 1: Créer `security-weekly.yml`**

Reprend le contenu de `owasp-weekly.yml` (scan OWASP Dependency-Check).

```yaml
name: Security Weekly

on:
  schedule:
    - cron: '0 3 * * 1'
  workflow_dispatch:

env:
  JAVA_VERSION: '21'

jobs:

  owasp-check:
    name: OWASP Dependency Check
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: temurin
          cache: maven
      - name: Cache base NVD
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository/org/owasp/dependency-check-data
          key: owasp-nvd-${{ github.run_id }}
          restore-keys: owasp-nvd-
      - name: OWASP — CVSS >= 7 = échec
        run: |
          chmod +x mvnw
          ./mvnw org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7
      - name: Upload rapport HTML
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: owasp-report-${{ github.run_number }}
          path: target/dependency-check-report.html
          retention-days: 30
```

- [ ] **Step 2: Supprimer les trois anciens workflows**

```bash
git rm .github/workflows/quality.yml .github/workflows/deploy.yml .github/workflows/owasp-weekly.yml
```

- [ ] **Step 3: Valider le nouveau workflow**

Run: `docker run --rm -v "$(pwd):/repo" --workdir /repo rhysd/actionlint:latest -color .github/workflows/security-weekly.yml && echo VALIDE`
Expected: `VALIDE`

- [ ] **Step 4: Vérifier l'état final du dossier workflows**

Run: `ls .github/workflows/`
Expected: exactement `ci.yml  deploy-prod.yml  deploy-staging.yml  security-weekly.yml`

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/security-weekly.yml
git commit -m "Feat: workflow sécurité hebdo, suppression des anciens workflows"
```

---

### Task 9: Guide de configuration du déploiement

**Files:**
- Create: `docs/deployment/cicd-setup.md`

- [ ] **Step 1: Créer le guide de setup**

```markdown
# Configuration de la CI/CD dony

## Vue d'ensemble

- `ci.yml` — quality gates sur chaque PR et push `main`.
- `deploy-staging.yml` — après une CI réussie sur `main` : build l'image,
  push sur `ghcr.io` (tags `sha-<court>` + `staging`), déploie sur le VPS staging.
- `deploy-prod.yml` — déclenchement manuel : promeut une image existante vers
  le VPS prod, après approbation.
- `security-weekly.yml` — scan OWASP hebdomadaire.

## GitHub Environments

Créer dans Settings → Environments :

### `staging`
Secrets : `OVH_HOST`, `OVH_USER`, `OVH_SSH_KEY` (VPS staging).

### `production`
- Règle de protection : activer « Required reviewers » (1 approbateur minimum).
- Secrets : `OVH_HOST`, `OVH_USER`, `OVH_SSH_KEY` (VPS prod),
  `SENTRY_AUTH_TOKEN`, `SENTRY_ORG`, `SENTRY_PROJECT`.

## Préparation des VPS

Sur chaque VPS, dans `~/dony/` :
- VPS staging : `docker-compose.staging.yml`, `nginx/nginx.staging.conf`,
  `nginx/certs/`, `nginx/www/`, `firebase-service-account.json`, `.env`.
- VPS prod : `docker-compose.prod.yml`, `nginx/nginx.conf`, `nginx/certs/`,
  `nginx/www/`, `firebase-service-account.json`, `.env`.

Le fichier `.env` contient les variables référencées par le Compose
(`DB_USERNAME`, `DB_PASSWORD`, clés Stripe, etc.). Staging utilise les clés
Stripe **test**, prod les clés **live**. Ne jamais committer ce fichier.

## Déployer en production

1. Vérifier que la version est validée en staging.
2. Actions → « Deploy Production » → « Run workflow ».
3. Saisir le tag : `staging` pour la dernière version, ou `sha-xxxxxxx` pour
   un rollback vers une version précise.
4. Approuver le déploiement quand GitHub le demande.

## Rollback

Relancer « Deploy Production » avec le `sha-xxxxxxx` de la version stable
précédente (visible dans l'onglet Packages du dépôt GitHub).
```

- [ ] **Step 2: Vérifier le rendu Markdown**

Run: `test -f docs/deployment/cicd-setup.md && echo OK`
Expected: `OK`

- [ ] **Step 3: Commit**

```bash
git add docs/deployment/cicd-setup.md
git commit -m "Docs: guide de configuration CI/CD"
```

---

## Vérification finale

- [ ] **Tous les workflows valides**

Run: `docker run --rm -v "$(pwd):/repo" --workdir /repo rhysd/actionlint:latest -color && echo "TOUS VALIDES"`
Expected: `TOUS VALIDES`

- [ ] **Les fichiers Compose valides**

Run: `DB_USERNAME=x DB_PASSWORD=x docker compose -f docker-compose.staging.yml config -q && DB_USERNAME=x DB_PASSWORD=x docker compose -f docker-compose.prod.yml config -q && echo "COMPOSE OK"`
Expected: `COMPOSE OK`

- [ ] **Build de l'image Docker fonctionnel**

Run: `docker build -t dony-back:plan-check . && echo "BUILD OK"`
Expected: `BUILD OK`

---

## Notes pour l'exécutant

- Ce plan ne touche **aucun code Java** — uniquement de la configuration.
  `./mvnw test` doit rester vert (aucun test impacté), mais aucun nouveau test
  n'est requis : il n'y a pas de logique métier ajoutée.
- Le profil `staging` n'a pas besoin d'entrée dans `application-test.yml` :
  les tests tournent sous le profil `test`.
- La validation réelle de la chaîne se fait au premier merge sur `main` : la
  CI doit passer, puis `deploy-staging.yml` doit se déclencher. Surveiller
  l'onglet Actions de GitHub à ce moment-là.
- Si `actionlint` n'est pas disponible en local, l'image Docker
  `rhysd/actionlint:latest` suffit (aucune installation).
