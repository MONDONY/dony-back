# Story 1.4 — PostgreSQL + Flyway Migrations V1 à V8 (Backend)

**Date:** 2026-04-19
**Status:** ✅ Complète

## Résumé
8 migrations Flyway créées et appliquées avec succès sur PostgreSQL 16. La base de données est structurée avec les schémas `public` et `kyc_schema`, le trigger d'immutabilité sur `audit_log` est opérationnel.

## Fichiers créés

- `src/main/resources/db/migration/V1__init_users.sql` — tables `users` et `user_roles`
- `src/main/resources/db/migration/V2__init_kyc_schema.sql` — schéma `kyc_schema` + table `kyc_verifications`
- `src/main/resources/db/migration/V3__announcements_bids.sql` — tables `announcements` et `bids`
- `src/main/resources/db/migration/V4__tracking_events.sql` — table `tracking_events` (avec support `offline_timestamp`)
- `src/main/resources/db/migration/V5__payments.sql` — table `payments` (escrow Stripe)
- `src/main/resources/db/migration/V6__disputes.sql` — table `disputes`
- `src/main/resources/db/migration/V7__audit_log.sql` — table `audit_log` + trigger PostgreSQL immutabilité
- `src/main/resources/db/migration/V8__cancellations.sql` — tables `cancellations` et `rematch_suggestions`

## Fichiers modifiés
Aucun — la configuration datasource et Flyway était déjà en place dans `application.yml`, `application-dev.yml` et `application-prod.yml` (Story 1.2).

## Critères d'acceptation couverts

- [x] Spring Boot démarre et les 8 migrations s'exécutent dans l'ordre sans erreur
- [x] `flyway_schema_history` contient 8 entrées avec statut `SUCCESS`
- [x] `audit_log` trigger testé : INSERT réussi, UPDATE bloqué avec `"Les entrées audit_log sont immuables — opération UPDATE interdite"`
- [ ] Soft delete `@Where` : sera appliqué à chaque entity JPA lors de sa création (Epics 2+)

## Décisions techniques

- **BIGSERIAL pour audit_log** : `id` est BIGSERIAL (pas UUID) pour préserver l'ordre d'insertion et simplifier les requêtes de pagination temporelle.
- **VARCHAR pour statuts** : tous les statuts (`status`, `role`, `refund_status`) sont `VARCHAR` et non des ENUMs PostgreSQL, conformément aux règles du projet (ENUMs difficiles à migrer).
- **DECIMAL(10,2) pour montants** : jamais FLOAT pour les valeurs financières.
- **TIMESTAMP WITH TIME ZONE** : toutes les colonnes de date sont `TIMESTAMP WITH TIME ZONE` (UTC).
- **Constraint `chk_bids_declared_value`** : la limite 500€ est enforced au niveau base de données en plus de la validation Spring Boot.
- **`kyc_schema.kyc_verifications`** : les colonnes sensibles (`id_document_encrypted`) sont chiffrées AES-256 au niveau applicatif avant persistance — le chiffrement se fera dans le converter JPA lors de l'implémentation de la KYC (Epic 2).
