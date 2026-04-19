# Stories 2.2 & 2.3 — Authentification biométrique et PIN (Backend)

**Date:** 2026-04-19
**Status:** ✅ Complètes (backend inchangé)

## Résumé
Les stories 2.2 et 2.3 sont entièrement côté Flutter. Le backend ne nécessite aucun nouvel endpoint : l'authentification biométrique et PIN valide le token Firebase déjà en cache, et la session est confirmée via `GET /api/v1/auth/me` (créé en Story 2.1). Le `FirebaseTokenFilter` déjà en place valide le token à chaque requête.

## Fichiers créés
Aucun.

## Fichiers modifiés
Aucun.

## Comment ça fonctionne (pour la maintenance)

### Pourquoi le backend n'a rien à faire

Le flux d'authentification locale fonctionne ainsi :
1. Flutter valide le PIN ou la biométrie **localement** (sans appel API)
2. Firebase maintient la session active via son token (TTL 1h, refresh automatique)
3. Dès que l'utilisateur est "déverrouillé" côté Flutter, les appels API suivants portent automatiquement `Authorization: Bearer <token>` via `AuthInterceptor`
4. Le `FirebaseTokenFilter` valide ce token à chaque requête — c'est la vraie "authentification" côté serveur

### Endpoint utilisé par le SplashScreen
`GET /api/v1/auth/me` (créé Story 2.1) — permet de vérifier si l'utilisateur Firebase est bien enregistré en base avant de décider de la route (`/auth/local` vs `/auth/role`).

## Décisions techniques
- **Pas de endpoint `/auth/login`** : inutile dans une architecture Firebase stateless. Le token Firebase est la preuve d'authentification — le backend le vérifie à chaque requête via le filtre.
- **Le PIN n'est jamais envoyé au backend** : il est validé localement contre la valeur stockée dans l'Android Keystore / iOS Keychain. Envoyer le PIN au serveur serait un anti-pattern de sécurité.
