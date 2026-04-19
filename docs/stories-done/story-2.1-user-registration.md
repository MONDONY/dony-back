# Story 2.1 — Inscription utilisateur (Backend)

**Date:** 2026-04-19
**Status:** ✅ Complète

## Résumé
Implémentation de l'inscription utilisateur via Firebase Phone Auth : création de `UserEntity`, `AuthController` (`POST /auth/register`), `AuthService`, et entrée audit log à la création.

## Fichiers créés
- `src/main/resources/db/migration/V9__add_kyc_status_phone_unique.sql` — ajout colonne `kyc_status` + contrainte UNIQUE sur `phone_number`
- `src/main/java/com/dony/api/auth/Role.java` — enum SENDER, TRAVELER, ADMIN
- `src/main/java/com/dony/api/auth/KycStatus.java` — enum PENDING, VERIFIED, REJECTED
- `src/main/java/com/dony/api/auth/UserStatus.java` — enum ACTIVE, SUSPENDED, BANNED
- `src/main/java/com/dony/api/auth/UserEntity.java` — entité JPA avec soft delete, roles en ElementCollection
- `src/main/java/com/dony/api/auth/UserRepository.java` — findByFirebaseUid, existsByPhoneNumber
- `src/main/java/com/dony/api/auth/dto/RegisterRequest.java` — record avec validation E.164 phone
- `src/main/java/com/dony/api/auth/dto/UserResponse.java` — record DTO réponse
- `src/main/java/com/dony/api/auth/AuthService.java` — logique création, idempotence sur firebaseUid, unicité phone
- `src/main/java/com/dony/api/auth/AuthController.java` — POST /auth/register + GET /auth/me
- `src/main/java/com/dony/api/common/AuditLogEntity.java` — entité audit_log (BIGSERIAL, append-only)
- `src/main/java/com/dony/api/common/AuditLogRepository.java` — repository audit log
- `src/main/java/com/dony/api/common/AuditService.java` — service partagé d'audit

## Fichiers modifiés
- `src/main/java/com/dony/api/auth/FirebaseTokenFilter.java` — chargement UserEntity depuis DB, vérification statut SUSPENDED/BANNED, attribution des rôles Spring Security

## Critères d'acceptation couverts
- [x] Firebase envoie un SMS de vérification au numéro saisi (côté Flutter)
- [x] Après validation du code SMS, un compte est créé en base de données
- [x] Numéro déjà existant → HTTP 409 avec message "Ce numéro est déjà associé à un compte"
- [x] Aucun nouveau compte n'est créé si le numéro existe déjà (NFR13)
- [x] `kycStatus` = `PENDING` à la création
- [x] Entrée `audit_log` avec `action = 'USER_CREATED'`

## Décisions techniques
- `POST /auth/register` est idempotent : si le firebaseUid existe déjà, retourne l'utilisateur existant (safe pour les retries réseau)
- Le rôle ADMIN ne peut pas être auto-attribué (HTTP 403 si demandé)
- `FirebaseTokenFilter` écrit directement un HTTP 403 JSON pour les comptes SUSPENDED/BANNED (bloque toutes les requêtes, y compris les endpoints publics)
- `AuditLogEntity` utilise `BIGSERIAL` (pas UUID) et n'hérite pas de `BaseEntity`
- La contrainte UNIQUE sur `phone_number` est ajoutée en V9 car V1 n'avait qu'un index simple
