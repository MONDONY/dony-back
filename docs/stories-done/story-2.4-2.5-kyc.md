# Stories 2.4 & 2.5 — KYC Stripe Identity (Backend)

**Date:** 2026-04-19
**Status:** ✅ Complètes

## Résumé
Implémentation du flux KYC complet via Stripe Identity : création de session de vérification, réception et traitement du webhook Stripe, mise à jour du statut KYC de l'utilisateur.

## Fichiers créés
- `src/main/java/com/dony/api/config/StripeConfig.java` — initialise `Stripe.apiKey` au démarrage via `@PostConstruct`
- `src/main/java/com/dony/api/common/EncryptionService.java` — chiffrement AES-256-GCM pour les données KYC sensibles
- `src/main/java/com/dony/api/kyc/KycVerificationStatus.java` — enum : PENDING, VERIFIED, REQUIRES_INPUT
- `src/main/java/com/dony/api/kyc/KycVerificationEntity.java` — entité JPA mappée sur `kyc_schema.kyc_verifications`
- `src/main/java/com/dony/api/kyc/KycRepository.java` — findByUserId, findByStripeVerificationSessionId
- `src/main/java/com/dony/api/kyc/KycService.java` — logique métier KYC
- `src/main/java/com/dony/api/kyc/KycController.java` — endpoints REST
- `src/main/java/com/dony/api/kyc/dto/KycSessionResponse.java` — record : stripeUrl, sessionId, status
- `src/main/java/com/dony/api/kyc/dto/KycStatusResponse.java` — record : kycStatus, verificationStatus
- `src/main/java/com/dony/api/kyc/events/UserKycVerifiedEvent.java` — event Spring publié après vérification réussie

## Fichiers modifiés
- `src/main/resources/application.yml` — ajout `app.encryption.key` (clé de chiffrement KYC)

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux — Story 2.4 (Création session)
1. Flutter appelle `POST /api/v1/kyc/session` avec le token Firebase
2. `KycController` extrait le Firebase UID depuis le `SecurityContext`
3. `KycService.createSession()` :
   - Récupère l'utilisateur par Firebase UID
   - Vérifie que `kycStatus != VERIFIED` (sinon 409 Conflict)
   - Crée une `VerificationSession` Stripe Identity (type DOCUMENT, liveness + selfie requis)
   - Crée ou met à jour le record `kyc_verifications` avec le nouveau session ID
   - Retourne l'URL Stripe à Flutter
4. Flutter ouvre cette URL dans une WebView

### Vue d'ensemble du flux — Story 2.5 (Webhook)
1. Stripe Identity envoie `POST /api/v1/kyc/webhook` (endpoint public)
2. `KycController.handleWebhook()` lit le body brut via `HttpServletRequest.getInputStream()`
3. `KycService.processWebhook()` :
   - Valide la signature Stripe via `Webhook.constructEvent(payload, sigHeader, webhookSecret)`
   - Si signature invalide → 400 Bad Request + log Sentry WARNING
   - Parse l'event type : `identity.verification_session.verified` ou `identity.verification_session.requires_input`
   - Retrouve le `KycVerificationEntity` par `stripeVerificationSessionId`
   - Met à jour `KycVerificationEntity.status` et `UserEntity.kycStatus`
   - Si VERIFIED : publie `UserKycVerifiedEvent` → futur listener notifications (Epic 8)
   - Crée entrée `audit_log`

### Points d'entrée API
- `POST /kyc/session` — auth requise (SENDER ou TRAVELER) — crée session Stripe Identity
- `GET /kyc/status` — auth requise — retourne le statut KYC actuel de l'utilisateur
- `POST /kyc/webhook` — **public** (déjà dans SecurityConfig `permitAll()`) — webhook Stripe

### Entités JPA impliquées
- `KycVerificationEntity` → table `kyc_schema.kyc_verifications`
  - `user_id` : FK vers `public.users`, UNIQUE (un seul record KYC par utilisateur)
  - `stripe_verification_session_id` : ID de session Stripe pour retrouver l'enregistrement dans le webhook
  - Pattern **find-or-create + update** : si un record existe pour cet utilisateur, on met à jour le session ID (l'utilisateur peut retenter après un rejet)
  - **Pas de `@Where(deleted_at IS NULL)`** : la contrainte UNIQUE sur user_id ne tient pas compte de `deleted_at` → pas de soft delete sur KYC

### Pièges et points d'attention
- **Body brut pour webhook** : `@RequestBody String` consommerait le body avant la validation de signature Stripe. On utilise `HttpServletRequest.getInputStream()` pour lire le body brut exact.
- **`StripeConfig` avec `@PostConstruct`** : `Stripe.apiKey` est une variable statique dans la lib Stripe. Si `StripeConfig` n'est pas chargé avant la première création de session, les appels Stripe échoueront avec "No API key provided". Le `@PostConstruct` garantit l'initialisation au démarrage.
- **Pattern find-or-create pour KycVerification** : La contrainte UNIQUE `uq_kyc_user_id` interdit deux records pour le même user. Si on appelle `save()` sur un nouveau record sans vérifier l'existence, on aura une violation de contrainte. → Toujours utiliser `findByUserId().orElseGet(...)`.
- **EncryptionService** : La clé est dérivée via SHA-256 depuis la chaîne configurée → n'importe quelle longueur de passphrase fonctionne. En production, `ENCRYPTION_KEY` doit être une variable d'environnement. Si la clé change, les données existantes ne pourront plus être déchiffrées.
- **`UserKycVerifiedEvent`** : publié dans le thread de la transaction KYC. Si le listener de notifications échoue, cela ne doit pas faire rollback la mise à jour KYC → les listeners doivent utiliser `@Async` ou `@TransactionalEventListener(phase = AFTER_COMMIT)`.

## Critères d'acceptation couverts
- [x] kycStatus = PENDING → POST /kyc/session → session Stripe créée → WebView ouverte
- [x] Stripe envoie webhook `verified` → signature validée → kycStatus = VERIFIED en base
- [x] Signature invalide → 400 Bad Request + log Sentry WARNING
- [x] Données KYC dans `kyc_schema` séparé → chiffrement AES-256 disponible via EncryptionService
- [x] Audit log créé pour KYC_SESSION_CREATED, KYC_VERIFIED, KYC_REJECTED

## Décisions techniques
- **Lecture body brut via `HttpServletRequest`** : nécessaire pour la validation de signature Stripe (le hash est calculé sur le payload exact reçu). Si Spring désérialise d'abord en String, l'encodage peut différer.
- **`setReturnUrl("dony://kyc/complete")`** : URL scheme custom interceptée par la WebView Flutter. Stripe redirige vers cette URL après vérification. La WebView intercepte et navigue vers `/kyc/status`.
- **`requireLiveCapture + requireMatchingSelfie`** : options Stripe pour maximiser la sécurité de la vérification. Peut être assoupli si le taux de rejet est trop élevé.
- **Pas de capture `selfieUrl` pour l'instant** : Stripe Identity gère le stockage des documents. On pourrait récupérer la selfie via l'API Stripe et la stocker chiffrée sur Hetzner, mais c'est hors scope MVP.

## ⚠️ À configurer avant la mise en production

- [ ] **Endpoint webhook Stripe** : enregistrer `https://api.dony.app/api/v1/kyc/webhook` dans le Dashboard Stripe → Developers → Webhooks → Add endpoint. Sélectionner les events `identity.verification_session.verified` et `identity.verification_session.requires_input`.
- [ ] **Secret webhook de production** : copier le `whsec_xxx` généré par le Dashboard (pas celui de `stripe listen`) et le définir dans la variable d'environnement `STRIPE_WEBHOOK_SECRET` sur le serveur.
- [ ] **Clé de chiffrement KYC** : remplacer la valeur par défaut `dony-dev-encryption-key-change-in-prod` par une clé aléatoire forte (min 32 caractères) dans la variable d'environnement `ENCRYPTION_KEY`. **Attention : si cette clé change après que des données KYC ont été chiffrées, elles ne pourront plus être déchiffrées.**
- [ ] **Stripe Identity en mode live** : activer Stripe Identity sur le compte live (pas test) dans le Dashboard Stripe. Les clés `pk_live_xxx` / `sk_live_xxx` doivent remplacer les clés test dans `application-prod.yml`.
- [ ] **Ne jamais utiliser `stripe listen`** en production — les webhooks doivent arriver directement de Stripe vers l'endpoint HTTPS public.
