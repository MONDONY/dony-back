# Yadony Complete Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remplacer complètement l'identité Dony par Yadony et le domaine `dony.store` par `yadony.com` dans le backend.

**Architecture:** Le renommage est réalisé par couches vérifiables : espace de noms Java, symboles Java, configuration applicative, infrastructure, puis documentation. Les migrations Flyway historiques constituent une zone immuable et sont exclues de tous les remplacements.

**Tech Stack:** Java 21, Spring Boot 3.4.x, Maven Wrapper, PostgreSQL/Flyway, Docker Compose, Nginx, Bash.

## Global Constraints

- La nouvelle marque active est `Yadony`, avec `yadony` et `YADONY` selon la casse.
- Le domaine actif est `yadony.com`, y compris `api.yadony.com` et `api-staging.yadony.com`.
- Le package racine devient `com.yadony.api`.
- Les préfixes de configuration et d'environnement deviennent `yadony` et `YADONY_*`.
- Les migrations sous `src/main/resources/db/migration/` restent strictement inchangées.
- Les deux documents de conception et de planification du 29 juillet 2026 sont
  des traces historiques autorisées à nommer l'ancienne identité.
- La modification locale antérieure de `src/main/resources/application-dev.yml` doit être conservée.
- Aucun commit ne doit être créé directement sur `main`.

---

### Task 1: Établir les garde-fous et l'état initial

**Files:**
- Inspect: `src/main/resources/db/migration/`
- Inspect: `src/main/resources/application-dev.yml`
- Create: `/tmp/yadony-flyway-before.sha256`

**Interfaces:**
- Consumes: état Git de la branche `codex/rename-yadony`
- Produces: empreinte de référence des migrations et inventaire des changements locaux

- [ ] **Step 1: Enregistrer l'empreinte des migrations**

```bash
find src/main/resources/db/migration -type f -print0 \
  | sort -z \
  | xargs -0 shasum -a 256 > /tmp/yadony-flyway-before.sha256
```

- [ ] **Step 2: Vérifier l'état de travail et la branche**

Run:

```bash
git branch --show-current
git status --short
git diff -- src/main/resources/application-dev.yml
```

Expected: branche `codex/rename-yadony`; seule la modification locale attendue et les documents de planification sont présents.

- [ ] **Step 3: Exécuter le test structurel rouge**

Run:

```bash
if git ls-files -z \
  | grep -zv '^src/main/resources/db/migration/' \
  | grep -zv '^docs/superpowers/specs/2026-07-29-yadony-complete-rename-design.md$' \
  | grep -zv '^docs/superpowers/plans/2026-07-29-yadony-complete-rename.md$' \
  | xargs -0 rg -l -i 'dony'; then
  exit 1
fi
```

Expected: FAIL, car l'ancien nom est encore présent hors migrations.

### Task 2: Renommer l'espace de noms et les symboles Java

**Files:**
- Move: `src/main/java/com/dony/` → `src/main/java/com/yadony/`
- Move: `src/test/java/com/dony/` → `src/test/java/com/yadony/`
- Modify: tous les fichiers `*.java` sous ces deux nouveaux répertoires
- Rename: `src/main/java/com/yadony/api/DonyBackApplication.java` → `src/main/java/com/yadony/api/YadonyBackApplication.java`
- Rename: `src/main/java/com/yadony/api/common/DonyBusinessException.java` → `src/main/java/com/yadony/api/common/YadonyBusinessException.java`
- Rename: `src/main/java/com/yadony/api/common/DonyNotFoundException.java` → `src/main/java/com/yadony/api/common/YadonyNotFoundException.java`
- Rename: `src/main/java/com/yadony/api/config/DonyConfig.java` → `src/main/java/com/yadony/api/config/YadonyConfig.java`
- Rename: `src/main/java/com/yadony/api/config/DonyConfigProperties.java` → `src/main/java/com/yadony/api/config/YadonyConfigProperties.java`
- Rename: tests Java dont le nom contient `Dony`

**Interfaces:**
- Consumes: package racine `com.dony.api` et symboles `Dony*`
- Produces: package racine `com.yadony.api` et symboles `Yadony*`

- [ ] **Step 1: Déplacer les arbres de packages**

Utiliser des déplacements Git afin de préserver l'historique :

```bash
mkdir -p src/main/java/com/yadony src/test/java/com/yadony
git mv src/main/java/com/dony/api src/main/java/com/yadony/api
git mv src/test/java/com/dony/api src/test/java/com/yadony/api
```

- [ ] **Step 2: Remplacer le package et les symboles dans les sources Java**

Appliquer, dans les fichiers Java suivis sous `src/main/java` et `src/test/java`, les transformations exactes :

```text
com.dony.api              -> com.yadony.api
DonyBackApplication       -> YadonyBackApplication
DonyBusinessException     -> YadonyBusinessException
DonyNotFoundException     -> YadonyNotFoundException
DonyConfigProperties      -> YadonyConfigProperties
DonyConfig                -> YadonyConfig
"Dony"                    -> "Yadony"
"dony"                    -> "yadony"
"DONY"                    -> "YADONY"
```

- [ ] **Step 3: Renommer les fichiers correspondant aux classes renommées**

Run:

```bash
git mv src/main/java/com/yadony/api/DonyBackApplication.java src/main/java/com/yadony/api/YadonyBackApplication.java
git mv src/main/java/com/yadony/api/common/DonyBusinessException.java src/main/java/com/yadony/api/common/YadonyBusinessException.java
git mv src/main/java/com/yadony/api/common/DonyNotFoundException.java src/main/java/com/yadony/api/common/YadonyNotFoundException.java
git mv src/main/java/com/yadony/api/config/DonyConfig.java src/main/java/com/yadony/api/config/YadonyConfig.java
git mv src/main/java/com/yadony/api/config/DonyConfigProperties.java src/main/java/com/yadony/api/config/YadonyConfigProperties.java
```

Renommer de la même façon chaque test dont le nom de fichier contient `Dony`.

- [ ] **Step 4: Vérifier la compilation Java**

Run:

```bash
./mvnw -DskipTests compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "refactor: renomme les packages Java en Yadony"
```

### Task 3: Renommer la configuration applicative et les tests de ressources

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-dev.yml`
- Modify: `src/main/resources/application-staging.yml`
- Modify: `src/test/resources/application-test.yml`
- Modify: `src/test/resources/application-e2e.yml`
- Modify: `src/test/resources/junit-platform.properties`
- Modify: `src/test/resources/features/**/*.feature`
- Modify: `.env.example`
- Modify: `.env.prod.template`
- Modify: `.env.staging.template`

**Interfaces:**
- Consumes: propriétés `dony.*`, variables `DONY_*`, artefact `dony-back`, schéma `dony://`
- Produces: propriétés `yadony.*`, variables `YADONY_*`, artefact `yadony-back`, schéma `yadony://`

- [ ] **Step 1: Remplacer les identifiants de configuration**

Appliquer les transformations avec respect de la casse :

```text
dony.store   -> yadony.com
dony://      -> yadony://
dony-back    -> yadony-back
dony_test    -> yadony_test
dony-test    -> yadony-test
dony.app     -> yadony.com
dony:        -> yadony:
DONY_        -> YADONY_
Dony         -> Yadony
dony         -> yadony
DONY         -> YADONY
```

Ne modifier aucun fichier sous `src/main/resources/db/migration/`.

- [ ] **Step 2: Vérifier le chargement du contexte de test**

Run:

```bash
./mvnw -Dtest=OpenApiIT,EmailOtpConfigurationGuardTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add pom.xml src/main/resources src/test/resources .env.example .env.prod.template .env.staging.template
git commit -m "config: bascule la configuration vers Yadony"
```

### Task 4: Renommer l'infrastructure et le déploiement

**Files:**
- Modify: `Dockerfile`
- Modify: `docker-compose.dev.yml`
- Modify: `docker-compose.staging.yml`
- Modify: `nginx/nginx.conf`
- Modify: `nginx/nginx.staging.conf`
- Modify: `.github/workflows/*.yml`
- Modify: `load-test/run_staging.sh`
- Modify: `load-test/staging.env.example`
- Modify: `load-test/STAGING.md`
- Modify: `scripts/*.sh`
- Modify: `scripts/*.sql` hors migrations Flyway
- Modify: `start-dev-email.sh`

**Interfaces:**
- Consumes: services, images, réseaux, volumes, bases, buckets et hôtes portant l'ancien nom
- Produces: équivalents Yadony et hôtes sous `yadony.com`

- [ ] **Step 1: Remplacer les noms d'infrastructure**

Appliquer les transformations exactes hors migrations :

```text
api-staging.dony.store -> api-staging.yadony.com
api.dony.store         -> api.yadony.com
dony.store             -> yadony.com
mondony/dony-back      -> yadony/yadony-back
MONDONY/dony-back      -> YADONY/yadony-back
dony-back              -> yadony-back
dony_                  -> yadony_
dony-                  -> yadony-
dony                    -> yadony
DONY                    -> YADONY
```

- [ ] **Step 2: Valider les fichiers Docker Compose**

Run:

```bash
docker compose -f docker-compose.dev.yml config >/dev/null
docker compose -f docker-compose.staging.yml config >/dev/null
```

Expected: les deux commandes se terminent avec le code `0`.

- [ ] **Step 3: Vérifier la syntaxe des scripts shell**

Run:

```bash
git ls-files -z '*.sh' | xargs -0 -n1 bash -n
```

Expected: code de sortie `0`.

- [ ] **Step 4: Commit**

```bash
git add Dockerfile docker-compose.dev.yml docker-compose.staging.yml nginx .github load-test scripts start-dev-email.sh
git commit -m "chore: renomme l infrastructure en Yadony"
```

### Task 5: Actualiser la documentation et les métadonnées restantes

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `DEPLOYMENT.md`
- Modify: `NEXT_STEPS.md`
- Modify: `GITHUB_SECRETS_SETUP.md`
- Modify: `docs/**/*.md`
- Modify: `.agents/skills/dony-back-rules/SKILL.md`
- Rename: `.agents/skills/dony-back-rules/` → `.agents/skills/yadony-back-rules/`

**Interfaces:**
- Consumes: documentation et compétence locale portant l'ancien nom
- Produces: documentation exclusivement Yadony, hors archives Flyway

- [ ] **Step 1: Remplacer les références documentaires**

Appliquer les mêmes règles de casse et de domaine que dans les tâches
précédentes. Les anciens documents de conception et plans restent modifiables,
car ils ne sont pas des migrations exécutables.

- [ ] **Step 2: Renommer la compétence locale**

Run:

```bash
git mv .agents/skills/dony-back-rules .agents/skills/yadony-back-rules
```

Modifier son en-tête en `name: yadony-back-rules` et son contenu pour employer
Yadony.

- [ ] **Step 3: Commit**

```bash
git add README.md CHANGELOG.md DEPLOYMENT.md NEXT_STEPS.md GITHUB_SECRETS_SETUP.md docs .agents
git commit -m "docs: actualise la documentation Yadony"
```

### Task 6: Vérification globale et bilan de migration

**Files:**
- Verify: tous les fichiers suivis par Git
- Verify: `src/main/resources/db/migration/`

**Interfaces:**
- Consumes: livrables des tâches 1 à 5
- Produces: preuve que le renommage est complet et que les migrations sont intactes

- [ ] **Step 1: Vérifier l'absence de l'ancien nom hors migrations**

Run:

```bash
if git ls-files -z \
  | grep -zv '^src/main/resources/db/migration/' \
  | grep -zv '^docs/superpowers/specs/2026-07-29-yadony-complete-rename-design.md$' \
  | grep -zv '^docs/superpowers/plans/2026-07-29-yadony-complete-rename.md$' \
  | xargs -0 rg -n -i 'dony'; then
  exit 1
fi
```

Expected: code de sortie `0` et aucune occurrence.

- [ ] **Step 2: Vérifier les checksums Flyway**

Run:

```bash
find src/main/resources/db/migration -type f -print0 \
  | sort -z \
  | xargs -0 shasum -a 256 \
  | diff -u /tmp/yadony-flyway-before.sha256 -
```

Expected: aucune différence.

- [ ] **Step 3: Vérifier le diff**

Run:

```bash
git diff --check
git status --short
git diff --stat HEAD~4
```

Expected: aucune erreur d'espacement et uniquement des changements liés au renommage.

- [ ] **Step 4: Exécuter toute la suite de tests**

Run:

```bash
./mvnw test
```

Expected: `BUILD SUCCESS`, zéro échec et zéro erreur.

- [ ] **Step 5: Vérifier le package**

Run:

```bash
./mvnw package -DskipTests
```

Expected: `BUILD SUCCESS` et création de `target/yadony-back-*.jar`.

- [ ] **Step 6: Consigner les actions externes dans le bilan**

Le bilan final doit signaler explicitement :

```text
- recréer ou renommer les secrets DONY_* en YADONY_*
- préparer les nouveaux DNS et certificats pour yadony.com
- créer ou migrer les bases, utilisateurs, volumes et buckets Yadony
- vérifier l'existence de l'organisation/dépôt GitHub YADONY/yadony-back
- mettre à jour les configurations Firebase, Stripe, Resend et S3 externes
```
