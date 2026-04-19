# Story 1.5 — Configuration Docker Compose (dev + prod) (Backend)

**Date:** 2026-04-19
**Status:** ✅ Complète

## Résumé
Dockerfile multi-stage et docker-compose.prod.yml créés pour le déploiement production sur Hetzner. Configuration Nginx avec rate limiting, SSL/TLS et reverse proxy vers l'API Spring Boot.

## Fichiers créés

- `Dockerfile` — multi-stage build: JDK 21 Alpine (Maven build) → JRE 21 Alpine (runtime), utilisateur non-root `dony`
- `.dockerignore` — exclut target/, logs, secrets et fichiers inutiles du contexte Docker
- `docker-compose.prod.yml` — 4 services : `api`, `db` (PostgreSQL 16, port non exposé), `db-backup` (pg_dump quotidien avec rotation 7 jours), `nginx` (port 80/443)
- `nginx/nginx.conf` — reverse proxy + rate limiting (30 req/min général, 5 req/min auth/kyc), SSL TLS 1.2/1.3, headers sécurité HSTS

## Fichiers modifiés
Aucun (`docker-compose.dev.yml` était déjà en place depuis Story 1.2).

## Critères d'acceptation couverts

- [x] `docker-compose.dev.yml` existant depuis Story 1.2 : PostgreSQL port 5432, Adminer port 8888
- [x] `Dockerfile` Spring Boot multi-stage créé : builder JDK → runtime JRE slim
- [x] `docker-compose.prod.yml` : service `api`, `db` (sans port exposé), `db-backup` (pg_dump quotidien), `nginx` (80/443)
- [x] `nginx/nginx.conf` : rate limiting `limit_req_zone` 30r/min API général, 5r/min auth/kyc, HTTP 429 si dépassé
- [x] Syntaxe `docker-compose.prod.yml` validée (`docker compose config --quiet`)
- [x] Syntaxe `nginx.conf` valide (erreur SSL attendue hors prod — certificats Let's Encrypt non présents en dev)

## Décisions techniques

- **Multi-stage Dockerfile** : séparation builder/runtime réduit l'image finale (JRE Alpine ~200MB vs JDK ~500MB). Aucun source ni Maven dans l'image finale.
- **Utilisateur non-root** : `adduser dony` dans l'image runtime — bonne pratique sécurité conteneurs.
- **`-XX:MaxRAMPercentage=75.0`** : JVM utilise 75% de la RAM allouée au container (adapté à Docker sur Hetzner CX31 avec 8GB RAM).
- **`resolver 127.0.0.11`** dans nginx.conf + `set $backend` : différer la résolution DNS du container `api` pour éviter l'échec de démarrage nginx si l'API redémarre. Résoudre via le DNS interne Docker.
- **db-backup sans port exposé** : la base de données prod n'est accessible que depuis le réseau interne Docker (`dony_internal`), pas depuis l'extérieur.
- **Rotation backups 7 jours** : `find /backups -mtime +7 -delete` dans le service `db-backup`.
