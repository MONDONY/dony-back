# Story 2.1 — Inscription utilisateur (Backend)

**Date:** 2026-04-19
**Status:** ✅ Complète

## Résumé
Implémentation de l'inscription d'un utilisateur après vérification Firebase Phone Auth : création du compte en base, attribution des rôles SENDER/TRAVELER, et enregistrement dans l'audit log.

## Fichiers créés
- `src/main/java/com/dony/api/auth/Role.java` — enum des rôles : SENDER, TRAVELER, ADMIN
- `src/main/java/com/dony/api/auth/KycStatus.java` — enum du statut KYC : PENDING, VERIFIED, REJECTED
- `src/main/java/com/dony/api/auth/UserStatus.java` — enum du statut compte : ACTIVE, SUSPENDED, BANNED
- `src/main/java/com/dony/api/auth/UserEntity.java` — entité JPA table `users`
- `src/main/java/com/dony/api/auth/UserRepository.java` — repository Spring Data
- `src/main/java/com/dony/api/auth/dto/RegisterRequest.java` — DTO de la requête d'inscription (record)
- `src/main/java/com/dony/api/auth/dto/UserResponse.java` — DTO de la réponse (record)
- `src/main/java/com/dony/api/auth/AuthService.java` — logique métier inscription/profil
- `src/main/java/com/dony/api/auth/AuthController.java` — endpoints REST
- `src/main/java/com/dony/api/common/AuditLogEntity.java` — entité JPA table `audit_log`
- `src/main/java/com/dony/api/common/AuditLogRepository.java` — repository audit log
- `src/main/java/com/dony/api/common/AuditService.java` — service pour écrire dans l'audit log
- `src/main/java/com/dony/api/common/DonyBusinessException.java` — exception métier avec HttpStatus, errorCode, title, detail
- `src/main/resources/db/migration/V9__add_kyc_status_phone_unique.sql` — colonne kyc_status + contrainte UNIQUE sur phone_number

## Fichiers modifiés
- `src/main/java/com/dony/api/auth/FirebaseTokenFilter.java` — vérification du statut utilisateur (SUSPENDED/BANNED) et gestion du cas "nouveau utilisateur non encore enregistré"
- `src/main/java/com/dony/api/common/GlobalExceptionHandler.java` — handler pour DonyBusinessException + log.error pour les erreurs inattendues

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. Flutter envoie `POST /api/v1/auth/register` avec Bearer token Firebase + body `{phoneNumber, roles}`
2. `FirebaseTokenFilter` valide le token → récupère l'UID Firebase → cherche l'utilisateur en base
   - Inexistant → authentifie avec UID + rôles vides (flow inscription normal)
   - Existant → vérifie SUSPENDED/BANNED, sinon attache ses rôles au contexte
3. `AuthController.register()` lit l'UID depuis `SecurityContextHolder`
4. `AuthService.register()` : si l'UID existe déjà (idempotence) → retourne le compte existant
5. Sinon : valide les rôles, vérifie unicité du téléphone, crée le UserEntity, sauvegarde
6. `auditService.log()` → INSERT dans `audit_log`
7. Retourne `UserResponse` avec HTTP 201

### Points d'entrée API
- `POST /api/v1/auth/register` — inscription, tout utilisateur Firebase authentifié (pas de rôle requis)
- `GET /api/v1/auth/me` — profil utilisateur connecté

Ces endpoints sont dans `/auth/**` marqué `permitAll()` dans SecurityConfig, mais `requireFirebaseUid()` vérifie manuellement l'authentification pour retourner un 401 ProblemDetail propre.

### Entités JPA impliquées
- `UserEntity` → table `users`
  - Étend `BaseEntity` (UUID id, created_at, updated_at, deleted_at)
  - `@Where(clause = "deleted_at IS NULL")` : toutes les requêtes Hibernate filtrent les soft-deleted
  - `@ElementCollection(fetch = EAGER)` pour les rôles → table `user_roles(user_id, role)`
- `AuditLogEntity` → table `audit_log`
  - ID BIGSERIAL (pas UUID) — ne peut pas étendre BaseEntity
  - `@Immutable` : OBLIGATOIRE — voir Pièges ci-dessous
  - `@JdbcTypeCode(SqlTypes.JSON)` pour le champ `payload` de type JSONB

### Logique métier critique
- **Idempotence** : même UID Firebase appelant `/register` deux fois → retourne le compte existant (safe retry)
- **Pas d'auto-attribution ADMIN** : refusé avec HTTP 403
- **Unicité du téléphone** : contrainte DB + vérification applicative avant INSERT → HTTP 409

### Events Spring publiés / écoutés
Aucun pour cette story.

### Pièges et points d'attention

**`@Immutable` sur AuditLogEntity — critique**
Hibernate 6 avec `GenerationType.IDENTITY` (BIGSERIAL) exécute l'INSERT immédiatement pour récupérer l'ID généré, puis émet un UPDATE lors du flush de transaction. Le trigger PostgreSQL sur `audit_log` interdit tout UPDATE → `JpaSystemException` au commit sans `@Immutable`. Cette annotation dit à Hibernate de ne jamais émettre d'UPDATE pour cette entité.

**`@Where` déprécié dans Hibernate 6.5**
Fonctionne encore mais génère un warning. Alternative : `@SQLRestriction`. Ne pas migrer maintenant (risque de régression sans bénéfice immédiat).

**`/auth/**` est permitAll() mais protégé manuellement**
Ne pas retirer le `permitAll()` — les appels sans token doivent atteindre le contrôleur pour recevoir un 401 ProblemDetail, pas une redirection Spring Security.

**Timestamp with time zone vs LocalDateTime**
Colonnes DB en `TIMESTAMP WITH TIME ZONE`, BaseEntity utilise `LocalDateTime`. La config `hibernate.jdbc.time_zone: UTC` gère la conversion. Ne pas supprimer cette config.

## Critères d'acceptation couverts
- [x] Inscription avec numéro E.164 + rôles → compte créé ACTIVE/PENDING en base
- [x] Double appel même UID → retourne le compte existant (idempotence)
- [x] Numéro déjà utilisé → HTTP 409
- [x] Rôle ADMIN auto-attribué → HTTP 403
- [x] Action tracée dans audit_log avec payload

## Décisions techniques

**DonyBusinessException unique plutôt qu'une exception par cas**
Centralise la logique dans GlobalExceptionHandler, produit des ProblemDetail cohérents partout.

**Idempotence sur l'UID Firebase (pas sur le numéro)**
L'UID est la clé d'identité Firebase. Si le client retente, il ne doit pas voir d'erreur 409.

**@ElementCollection pour les rôles**
Les rôles sont de simples enum strings, pas des entités. Plus simple qu'une relation @ManyToMany avec une entité RoleEntity.
