# Story — Auth Modes : Email OTP & OAuth Direct (Backend)

**Date :** 2026-05-19  
**Status :** ✅ Complète

---

## Résumé

Ajout d'un nouveau mode d'authentification par email OTP et correction du flux d'inscription OAuth (Google/Apple) pour les nouveaux utilisateurs. Avant cette story, `POST /auth/register` exigeait un `phoneNumber` E.164 — rendant impossible l'inscription d'un utilisateur Google/Apple sans numéro de téléphone. Deux nouvelles choses ont été implémentées : (1) le package `emailotp` qui gère l'envoi et la vérification d'OTP par email via Resend, et (2) le routage multi-provider dans `AuthService.createUser` selon le `sign_in_provider` du token Firebase.

---

## Fichiers créés

| Fichier | Rôle |
|---------|------|
| `src/main/resources/db/migration/V88__email_otp_tokens.sql` | Table `email_otp_tokens` + index `idx_email_otp_email` |
| `src/main/java/com/dony/api/emailotp/EmailOtpEntity.java` | Entité JPA non-BaseEntity (pas de soft-delete, immutable après `used_at`) |
| `src/main/java/com/dony/api/emailotp/EmailOtpRepository.java` | `findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc` + `countByEmailSince` |
| `src/main/java/com/dony/api/emailotp/EmailOtpProperties.java` | `@ConfigurationProperties("dony.email")` : resend-api-key, from-address, otp-template |
| `src/main/java/com/dony/api/emailotp/ResendEmailService.java` | Appel REST `POST https://api.resend.com/emails` via `RestClient` |
| `src/main/java/com/dony/api/emailotp/EmailOtpService.java` | Logique métier : génération SecureRandom, rate-limit, BCrypt, vérification, custom token Firebase |
| `src/main/java/com/dony/api/emailotp/dto/EmailOtpSendRequest.java` | `{ email }` validé `@Email @NotBlank` |
| `src/main/java/com/dony/api/emailotp/dto/EmailOtpSendResponse.java` | `{ expiresAt: Instant }` |
| `src/main/java/com/dony/api/emailotp/dto/EmailOtpVerifyRequest.java` | `{ email, code }` — `code` validé `@Pattern(regexp="\\d{6}")` |
| `src/main/java/com/dony/api/emailotp/dto/EmailOtpVerifyResponse.java` | `{ customToken: String }` |
| `src/main/java/com/dony/api/emailotp/EmailOtpController.java` | `POST /auth/email-otp/send` + `POST /auth/email-otp/verify` (public, pas d'auth Firebase) |
| `src/test/java/com/dony/api/emailotp/EmailOtpServiceTest.java` | 10 tests unitaires (sendOtp × 2, verifyOtp × 8) |
| `src/test/java/com/dony/api/emailotp/EmailOtpControllerIntegrationTest.java` | 8 tests MockMvc (200/422/429 send, 200/400/422/429 verify) |
| `src/test/java/com/dony/api/emailotp/ResendEmailServiceTest.java` | 1 test unitaire (couverture de la chaîne RestClient) |

## Fichiers modifiés

| Fichier | Ce qui a changé |
|---------|-----------------|
| `src/main/resources/application.yml` | Ajout section `dony.email` (resend-api-key, from-address, otp-template) |
| `src/main/resources/application-test.yml` | Valeurs de test pour `dony.email` (clé bidon, template simple) |
| `src/main/java/com/dony/api/config/SecurityConfig.java` | Bean `BCryptPasswordEncoder` (strength 10) ; endpoints `/auth/email-otp/**` ajoutés aux routes publiques |
| `src/main/java/com/dony/api/config/FirebaseConfig.java` | Bean `FirebaseAuth` injectable (optionnel en test) |
| `src/main/java/com/dony/api/auth/FirebaseTokenFilter.java` | Ligne 78 : `setAuthentication(uid, null, ...)` → `setAuthentication(uid, decoded, ...)` pour que le `FirebaseToken` soit disponible en `SecurityContext.credentials` même pour les nouveaux utilisateurs |
| `src/main/java/com/dony/api/auth/UserRepository.java` | Ajout `existsByEmail(String)` + `findByEmail(String)` |
| `src/main/java/com/dony/api/auth/dto/RegisterRequest.java` | `phoneNumber` devient `@Nullable` (était `@NotBlank`) ; ajout champ `@Nullable @Email email` |
| `src/main/java/com/dony/api/auth/AuthService.java` | `register()` reçoit maintenant `FirebaseToken decodedToken` ; `createUser()` routé par `sign_in_provider` |
| `src/main/java/com/dony/api/auth/AuthController.java` | Extrait `FirebaseToken` du `SecurityContext.credentials` et le passe à `authService.register()` |
| `src/test/java/com/dony/api/auth/AuthServiceTest.java` | Tests existants adaptés au nouveau `RegisterRequest` et signature `register(uid, token, req)` ; +7 tests multi-provider |

---

## Comment ça fonctionne

### Vue d'ensemble — Flux email OTP

```
Client                     Backend
  │                           │
  │ POST /auth/email-otp/send │
  │ { email }                 │ → rate-limit 3/5min (SERIALIZABLE)
  │                           │ → SecureRandom 6 chiffres
  │                           │ → BCrypt(code) stocké
  │ ← { expiresAt }           │ → Resend API → email envoyé
  │                           │
  │ POST /auth/email-otp/verify │
  │ { email, code }           │ → cherche token non-utilisé
  │                           │ → BCrypt.matches() (anti-timing-attack)
  │                           │ → vérifie expiration APRÈS BCrypt
  │                           │ → marque used_at
  │ ← { customToken }         │ → FirebaseAuth.createCustomToken(email)
  │                           │
  │ (client: signInWithCustomToken)
  │                           │
  │ POST /auth/register       │
  │ { email, roles }          │ → provider=custom → vérifie uid==email
  │   Bearer: customToken     │ → crée UserEntity, setEmail
  │ ← UserResponse 201        │
```

### Vue d'ensemble — Flux OAuth (Google/Apple) nouveau user

```
Client                     Backend
  │                           │
  │ (OAuth Firebase hors-ligne)│
  │ POST /auth/register       │
  │ { roles }                 │ → extraire sign_in_provider du token
  │   Bearer: OAuthToken      │   ("google.com" ou "apple.com")
  │                           │ → email = decodedToken.getEmail()
  │                           │ → si email existe déjà → retour idempotent
  │ ← UserResponse 201        │ → sinon crée UserEntity, setEmail
```

### Points d'entrée API

| Endpoint | Auth requise | Description |
|----------|-------------|-------------|
| `POST /auth/email-otp/send` | Non | Envoie OTP — rate-limit 3 envois/5min |
| `POST /auth/email-otp/verify` | Non | Vérifie OTP — retourne Firebase Custom Token |
| `POST /auth/register` | Bearer Firebase | Inchangé en surface ; routage interne par provider |

### Routage multi-provider dans `AuthService.createUser`

Le `sign_in_provider` est extrait depuis `decodedToken.getClaims().get("firebase")` (cast en `Map<?,?>` puis `get("sign_in_provider")`). La méthode `FirebaseToken.getFirebase()` n'existe pas dans firebase-admin 9.4.2.

| `sign_in_provider` | Comportement |
|--------------------|-------------|
| `phone` | `phoneNumber` obligatoire dans le body ; vérifie doublon ; `setPhoneNumber` |
| `google.com`, `apple.com` | `null`-check sur `decodedToken` d'abord ; email depuis le token ; idempotent si email existant |
| `custom` | Vérifie `firebaseUid.equalsIgnoreCase(request.email())` (sécurité : pour un custom token, l'UID Firebase = l'email passé à `createCustomToken`) ; idempotent si email existant |
| autre | 422 `invalid-provider` |

### Entité `EmailOtpEntity`

Table `email_otp_tokens` — n'étend pas `BaseEntity` (pas besoin de soft-delete). Champs : `id UUID`, `email VARCHAR(255)`, `codeHash VARCHAR(60)`, `expiresAt TIMESTAMP`, `usedAt TIMESTAMP` (null = non utilisé), `attempts INT`, `createdAt TIMESTAMP`. La query `findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc` retourne le token le plus récent non encore consommé.

### Sécurité anti-timing-attack

Dans `EmailOtpService.verifyOtp`, **`passwordEncoder.matches()` est appelé avant** le check `isAfter(expiresAt)`. Sans ça, un attaquant peut déduire si un token existe (réponse rapide sur token expiré vs lente sur BCrypt). La valeur booléenne est stockée dans `boolean validCode` et testée après.

### Sécurité anti-usurpation (custom provider)

`FirebaseAuth.createCustomToken(email)` crée un token dont l'UID Firebase est exactement l'email fourni. Lors du `POST /auth/register`, la vérification `firebaseUid.equalsIgnoreCase(request.email())` garantit qu'un attaquant ne peut pas s'inscrire avec l'email de quelqu'un d'autre en utilisant son propre custom token.

### `FirebaseTokenFilter` — fix credentials null

Avant le fix, les nouveaux utilisateurs (non encore en base) faisaient que `FirebaseTokenFilter` passait `null` comme credentials. `AuthController.register()` castait donc `null` en `FirebaseToken`, et `createUser()` ne pouvait pas lire le `sign_in_provider`. Fix : la ligne 78 passe maintenant `decoded` (le `FirebaseToken` décodé) à `setAuthentication()`, même quand l'utilisateur n'est pas encore en base.

### Pièges et points d'attention

- **`ResendEmailService` — constructeur `@Autowired` obligatoire :** avec deux constructeurs (public + package-private pour tests), Spring exige `@Autowired` sur le constructeur principal sinon il cherche un constructeur no-arg et échoue avec `NoSuchMethodException`.
- **Isolation SERIALIZABLE sur `sendOtp` :** protège le rate-limit counter contre les requêtes concurrentes (race condition si deux requêtes simultanées pour le même email).
- **`@Autowired(required = false)` sur `FirebaseAuth` dans `EmailOtpService` :** permet aux tests sans contexte Firebase de fonctionner. En l'absence de `FirebaseAuth`, `verifyOtp` retourne `null` au lieu du custom token.
- **Migration V88 :** `email_otp_tokens` n'est pas dans le schéma `kyc_schema` — c'est une donnée temporelle (TTL 10 min), pas une donnée personnelle sensible à long terme.

---

## Critères d'acceptation couverts

- [x] `POST /auth/email-otp/send` — retourne 200 + `expiresAt` pour un email valide
- [x] `POST /auth/email-otp/send` — retourne 422 pour un email invalide
- [x] `POST /auth/email-otp/send` — retourne 429 après 3 envois en 5 minutes pour le même email
- [x] `POST /auth/email-otp/verify` — retourne 200 + `customToken` pour un code valide et non expiré
- [x] `POST /auth/email-otp/verify` — retourne 400 pour un code invalide (incrémente `attempts`)
- [x] `POST /auth/email-otp/verify` — retourne 400 pour un token expiré
- [x] `POST /auth/email-otp/verify` — retourne 429 après 5 tentatives échouées
- [x] `POST /auth/email-otp/verify` — code non-numérique retourne 422 (validation `@Pattern`)
- [x] `POST /auth/register` provider `phone` — `phoneNumber` obligatoire, doublon → 409
- [x] `POST /auth/register` provider `google.com`/`apple.com` — email extrait du token ; idempotent si existant
- [x] `POST /auth/register` provider `custom` — vérifie uid == email (anti-usurpation) ; idempotent si existant
- [x] `POST /auth/register` provider inconnu → 422

---

## Tests

```bash
./mvnw test   # → 1006 tests run, 0 failures, 0 errors
./mvnw test jacoco:report
# emailotp [LINE]: 98.9% ✅
# auth     [LINE]: 90.7% ✅
```

Tests ajoutés ou modifiés :
- `EmailOtpServiceTest` — 10 tests unitaires
- `EmailOtpControllerIntegrationTest` — 8 tests MockMvc
- `ResendEmailServiceTest` — 1 test unitaire (chaîne RestClient)
- `AuthServiceTest` — 7 tests `RegisterWithProvider` + tests existants adaptés

---

## Décisions techniques

| Décision | Raison |
|----------|--------|
| Resend comme service email | API simple (1 appel REST), pas de SDK lourd, DX ergonomique |
| BCrypt pour stocker le code OTP | Cohérence avec la sécurité du projet (même `PasswordEncoder` que les mots de passe) |
| Rate-limit applicatif (pas Nginx) | Nginx rate-limit est global sur `/auth/**` — il faut une granularité par email |
| `SERIALIZABLE` sur `sendOtp` | Protection contre la race condition sur le compteur de rate-limit |
| BCrypt avant expiration dans `verifyOtp` | Mitigation timing-attack : toujours passer par BCrypt peu importe l'état du token |
| `sign_in_provider` via `getClaims().get("firebase")` | Firebase Admin SDK 9.4.2 n'expose pas `getFirebase()` — il faut traverser la map des claims |
| Vérification `firebaseUid == email` pour custom provider | `createCustomToken(email)` pose l'email comme UID Firebase — vérification nécessaire pour éviter l'usurpation d'identité |
| `@Autowired` sur le constructeur principal de `ResendEmailService` | Spring exige une annotation explicite quand plusieurs constructeurs coexistent |
