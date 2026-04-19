# Story 1.6 — CI/CD GitHub Actions (Backend)

**Date:** 2026-04-19
**Status:** ✅ Complète

## Résumé
Pipeline CI/CD en 3 jobs : tests automatiques → build & push image Docker sur ghcr.io → déploiement SSH sur Hetzner avec health check et notification Sentry.

## Fichiers créés

- `.github/workflows/deploy.yml` — pipeline complet CI/CD

## Fichiers modifiés

- `docker-compose.prod.yml` — correction du nom d'image (`ghcr.io/mondony/dony-back:latest`)

## Critères d'acceptation couverts

- [x] Trigger sur push `main` uniquement
- [x] Tests Spring Boot (`./mvnw test`) — pipeline échoue si un test est rouge
- [x] Build JAR (`./mvnw package -DskipTests`)
- [x] Docker build + push vers `ghcr.io/mondony/dony-back`
- [x] Déploiement SSH Hetzner : `docker compose pull` + `up -d --no-deps api`
- [x] Health check post-déploiement : 12 tentatives × 5s = 60s max
- [x] Notification Sentry à chaque déploiement réussi

## Secrets GitHub à configurer

Dans **Settings → Secrets and variables → Actions** du repo `dony-back` :

| Secret | Valeur |
|--------|--------|
| `HETZNER_HOST` | IP du VPS Hetzner |
| `HETZNER_USER` | Utilisateur SSH (ex: `ubuntu`) |
| `HETZNER_SSH_KEY` | Clé privée SSH (contenu du fichier `~/.ssh/id_rsa`) |
| `SENTRY_AUTH_TOKEN` | Token Sentry (Settings → Auth Tokens) |
| `SENTRY_ORG` | Slug de l'organisation Sentry |
| `SENTRY_PROJECT` | Slug du projet Sentry backend |

`GITHUB_TOKEN` est automatiquement injecté par GitHub Actions (pour ghcr.io).

## Décisions techniques

- **H2 in-memory pour les tests CI** : le profil `test` utilise H2 — pas besoin de service PostgreSQL dans le runner GitHub Actions, ce qui accélère le pipeline.
- **Tags d'image** : `latest` (branche main) + `sha-<commit>` pour traçabilité et rollback possible.
- **`--no-deps api`** : ne redémarre que le container `api`, pas la base de données ni nginx — zéro downtime DB.
- **`docker image prune -f`** : nettoie les anciennes images après déploiement pour libérer l'espace disque du VPS.
- **Environment `production`** : le job `deploy` est sous protection GitHub Environments — possibilité d'ajouter des reviewers obligatoires avant déploiement.
