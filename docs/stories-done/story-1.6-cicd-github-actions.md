# Story 1.6 — CI/CD GitHub Actions (Backend)

**Date:** 2026-04-19
**Status:** ✅ Complète

## Résumé
Pipeline CI/CD complet avec 4 gates qualité en parallèle bloquant le déploiement, suivi d'un build Docker, d'un déploiement SSH sur VPS OVH avec health check et notification Sentry. Scan OWASP hebdomadaire indépendant.

## Fichiers créés / modifiés

- `.github/workflows/deploy.yml` — pipeline complet
- `pom.xml` — ajout plugins JaCoCo (couverture) et SpotBugs (analyse statique)

## Architecture du pipeline

```
push main / PR
    │
    ├── Gate 1: Tests + JaCoCo        ─┐
    ├── Gate 2: SpotBugs (HIGH bugs)   ├─ parallèles — tous doivent passer
    ├── Gate 3: Trivy (CVE CRITICAL)   │
    └── Gate 4: Hadolint Dockerfile   ─┘
                    │
              build-push (main uniquement)
              Docker image → ghcr.io/mondony/dony-back
                    │
              deploy → SSH OVH → health check 60s → Sentry

Lundi 03:00 UTC (cron séparé)
    └── OWASP Dependency Check (CVSS ≥ 7)
```

## Critères d'acceptation couverts

- [x] Tests Spring Boot — pipeline échoue si un test est rouge
- [x] SpotBugs — bugs priorité HIGH bloquent le déploiement
- [x] Trivy — CVE CRITICAL ou HIGH dans les dépendances bloquent le déploiement
- [x] Hadolint — mauvaises pratiques Dockerfile bloquent le déploiement
- [x] Build JAR + Docker push vers ghcr.io (seulement si les 4 gates passent)
- [x] Déploiement SSH sur VPS OVH
- [x] Health check post-déploiement : 12 tentatives × 5s = 60s max
- [x] Notification Sentry à chaque déploiement réussi
- [x] Scan OWASP hebdomadaire (lundi 03:00 UTC, non bloquant pour les pushs quotidiens)
- [x] Sur PR : gates uniquement (pas de build ni déploiement)

## Secrets GitHub à configurer

Dans **Settings → Secrets and variables → Actions** du repo `dony-back` :

| Secret | Valeur |
|--------|--------|
| `OVH_HOST` | IP du VPS OVH |
| `OVH_USER` | Utilisateur SSH (ex: `ubuntu` ou `debian`) |
| `OVH_SSH_KEY` | Clé privée SSH (contenu entier de `~/.ssh/id_rsa`) |
| `SENTRY_AUTH_TOKEN` | Token Sentry (Settings → Auth Tokens) |
| `SENTRY_ORG` | Slug organisation Sentry |
| `SENTRY_PROJECT` | Slug projet Sentry backend |

`GITHUB_TOKEN` est injecté automatiquement par GitHub Actions pour ghcr.io.

## Décisions techniques

- **4 gates en parallèle** : les checks s'exécutent simultanément — temps CI minimal.
- **H2 pour les tests** : le profil `test` utilise H2 in-memory, pas besoin de PostgreSQL dans le runner.
- **Trivy fs vs image scan** : scan du filesystem (pom.xml + JAR) avant le build image — plus rapide, détecte les CVE dans les dépendances Maven.
- **SARIF upload** : les résultats Trivy apparaissent dans l'onglet "Security" du repo GitHub.
- **OWASP hebdomadaire** : la base NVD met 15-30 min à télécharger — trop lent pour chaque push. Exécuté le lundi matin via cron.
- **`--no-deps api`** : seul le container API est redémarré sur OVH, pas la DB ni Nginx — zéro downtime base de données.
- **Environment `production`** : peut être configuré avec des reviewers obligatoires dans GitHub Environments avant tout déploiement.
- **Sur PR** : seules les gates tournent, jamais de déploiement accidentel.
