# Story 1.7 — Configuration Sentry (Backend)

**Date:** 2026-04-19
**Status:** ✅ Complète

## Résumé
Sentry est configuré sur le backend Spring Boot pour capturer les erreurs de production avec le contexte utilisateur Firebase et sans données PII.

## Fichiers modifiés
- `src/main/java/com/dony/api/common/GlobalExceptionHandler.java` — ajout de `Sentry.captureException(ex)` dans le handler générique (Exception.class) pour garantir la remontée dans Sentry des erreurs inattendues

## Fichiers déjà présents (non modifiés)
- `pom.xml` — dépendance `sentry-spring-boot-starter-jakarta` déjà présente
- `src/main/resources/application-prod.yml` — `sentry.dsn`, `sentry.environment=production`, `sentry.traces-sample-rate=0.1`, `sentry.send-default-pii=false`
- `src/main/resources/application-dev.yml` — `sentry.enabled=false`

## Critères d'acceptation couverts
- [x] Exception non gérée capturée et envoyée dans Sentry (`Sentry.captureException` dans `handleGeneric`)
- [x] Contexte utilisateur Firebase : le `sentry-spring-boot-starter` lit automatiquement le principal Spring Security (Firebase UID défini par `FirebaseTokenFilter`)
- [x] Données PII masquées : `send-default-pii=false` — email et téléphone ne sont pas envoyés

## Décisions techniques
- Seul le handler `Exception.class` appelle `Sentry.captureException` explicitement. Les exceptions métier (`DonyBusinessException`, `DonyNotFoundException`) et de validation ne polluent pas Sentry car elles sont attendues.
- Le `sentry-spring-boot-starter` auto-configure `SentrySpringFilter` qui attache le user context depuis `SecurityContextHolder` (Firebase UID = `user.id` dans Sentry).
