# Audit de sécurité dony — 2026-05-03

**Périmètre :** backend Spring Boot (`dony-back/`) + frontend Flutter (`dony_app/`).
**Méthode :** 4 sous-agents en parallèle (back critique, back autres packages, Flutter, dates/fuseaux), puis correctifs et tests.
**Branche :** `security-audit-2026-05-03` sur les deux repos. **`main` n'a pas été modifiée.**

## Résumé exécutif

| Sévérité | Findings | Corrigés | Documentés (action manuelle requise) |
|---|---|---|---|
| **CRITICAL** | 8 | 4 | 4 |
| **HIGH** | 21 | 9 | 12 |
| **MEDIUM** | 22 | 4 | 18 |
| **LOW** | 22 | 1 | 21 |
| **TOTAL** | **73** | **18** | **55** |

État des tests : **`./mvnw test`** → 339 tests / 0 échec / 5 skipped. **`flutter test`** → 1 005 / 4 échecs (pré-existants, refactor map en cours, sans rapport avec les fixes appliqués).

Couverture JaCoCo backend globale : 70 % (objectif projet 90 % — non atteint **avant** l'audit déjà ; l'audit n'a pas dégradé le chiffre, il a ajouté des tests). Couverture Flutter non recalculée (4 échecs pré-existants empêchent la génération du rapport propre).

### Top 5 actions à faire **manuellement** dès le matin

1. **Révoquer la clé Firebase service-account dans GCP IAM** (`firebase-adminsdk-fbsvc@dony-36cb2.iam.gserviceaccount.com`) puis supprimer `src/main/resources/firebase-service-account.json` du disque et passer par `GOOGLE_APPLICATION_CREDENTIALS` monté en secret. La clé privée RSA est actuellement dans le working tree.
2. **Définir un `ENCRYPTION_KEY` de production** robuste (32 bytes base64) dans le secret manager. L'application refuse maintenant de démarrer sans cette variable — mais aucune donnée KYC chiffrée n'existe encore avec une clé "vraie", donc à faire avant tout chiffrement réel.
3. **Activer la vérification KYC** dans `AnnouncementService.java:154` et `BidService.java:58` (blocs `// TODO Réactiver avant la prod`). Non auto-corrigé pour ne pas casser des tests qui ne créent pas d'utilisateurs `VERIFIED`. Voir section dédiée plus bas.
4. **Configurer un keystore Android release** pour remplacer le debug-signing dans `android/app/build.gradle.kts:53-55` (sinon n'importe qui peut signer un APK avec la même empreinte).
5. **Restreindre la clé Google Maps** dans GCP Console au SHA-1 `release` + package `com.dony.dony` (et bundle ID iOS).

---

## 1. Backend — packages critiques (auth, kyc, payments, config)

### CRITICAL

#### [CRITICAL] Clé privée Firebase service-account dans le working tree
- **Fichier :** `src/main/resources/firebase-service-account.json`
- **Statut :** ❌ NON CORRIGÉ AUTOMATIQUEMENT — révocation manuelle requise
- **Impact :** impersonation totale de tout utilisateur Firebase (custom tokens, Firestore, FCM).
- **Action :** révoquer + rotate dans GCP IAM, supprimer le fichier, charger via `GoogleCredentials.getApplicationDefault()` avec secret monté.
- **Test à ajouter :** check de build (Gradle ou unit test) qui échoue si le JSON est présent dans l'artefact.

#### [CRITICAL] Clé de chiffrement KYC — fallback hardcodé
- **Fichier :** `src/main/resources/application.yml:80`
- **Statut :** ✅ CORRIGÉ — `${ENCRYPTION_KEY:?ENCRYPTION_KEY is required}` (fail-fast au démarrage). Les profils `test` et `e2e` ont une valeur de test dédiée pour ne pas casser les tests.
- **Suite à donner :** définir une vraie clé en production.

#### [CRITICAL] DB password et `dony.internal.secret` hardcodés dans `application-dev.yml`
- **Fichier :** `src/main/resources/application-dev.yml:5,40`
- **Statut :** ✅ PARTIELLEMENT CORRIGÉ — les valeurs sont maintenant des `${POSTGRES_PASSWORD:...}` et `${INTERNAL_SHARED_SECRET:...}` ; un dev local sans env var continue de fonctionner avec une valeur explicitement marquée `change-me`.
- **Suite à donner :** rotation du secret existant (dev DB et internal secret) puisqu'ils ont été commités sur l'historique git.

#### [CRITICAL] Données KYC non chiffrées au niveau colonne
- **Fichiers :** `KycVerificationEntity.java:27-32`, `KycService.java`
- **Statut :** ❌ NON CORRIGÉ AUTOMATIQUEMENT — refonte du flux nécessaire
- **Description :** les colonnes `idDocumentEncrypted` et `selfieUrl` portent un commentaire « encrypted via EncryptionService — applied in KycService layer », mais aucun appel `EncryptionService.encrypt(...)` n'existe. Le contenu est en clair.
- **Action :**
  ```java
  @Converter
  public class EncryptedStringConverter implements AttributeConverter<String, String> {
      private final EncryptionService enc;
      public String convertToDatabaseColumn(String s) { return s == null ? null : enc.encrypt(s); }
      public String convertToEntityAttribute(String s) { return s == null ? null : enc.decrypt(s); }
  }
  ```
  Annoter `idDocumentEncrypted` et `selfieUrl` avec `@Convert(converter = EncryptedStringConverter.class)`. Toujours servir `selfieUrl` via `StorageService.generatePresignedUrl(..., Duration.ofMinutes(60))`.
- **Test :** persister un `KycVerificationEntity`, faire un SELECT JDBC brut, vérifier que la colonne contient du chiffré, pas du clair.

#### [CRITICAL] `/internal/messaging/notify` exposé `permitAll` avec comparaison `String.equals` du secret
- **Fichiers :** `SecurityConfig.java:53`, `MessagingNotifyController.java`
- **Statut :** ✅ CORRIGÉ partiellement — `MessageDigest.isEqual` (constant-time) + `DonyBusinessException` pour RFC 7807. **Le whitelist `permitAll` du chemin reste** — à supprimer en bindant `/internal/**` à localhost depuis Nginx (action infra, non couverte ici).

### HIGH

#### [HIGH] `/auth/**` est trop large dans le `permitAll`
- **Fichier :** `SecurityConfig.java:42`
- **Statut :** ❌ NON CORRIGÉ AUTOMATIQUEMENT — risque de régression sur `AuthController` qui s'appuie aussi sur `requireFirebaseUid()`.
- **Action :** restreindre à `requestMatchers(HttpMethod.POST, "/auth/register").permitAll()` et déplacer le reste sous `.anyRequest().authenticated()`. Désactiver `/swagger-ui/**` en `prod` via `@Profile`.
- **Test :** intégration `PATCH /auth/me` sans token → 401 émis par Spring Security (et plus par le contrôleur).

#### [HIGH] `FirebaseTokenFilter` authentifie sans rôles si la DB est down
- **Fichier :** `FirebaseTokenFilter.java:91-95`
- **Statut :** ❌ NON CORRIGÉ — fail-open sur erreur DB.
- **Action :** sur `DataAccessException`, vider le contexte et renvoyer 503 ProblemDetail. Ne jamais appeler `setAuthentication(uid, List.of())`.

#### [HIGH] `KycController` sans `@PreAuthorize`, sans rate-limit
- **Fichier :** `KycController.java:28-38`
- **Statut :** ❌ NON CORRIGÉ — implique d'ajouter Bucket4j ou équivalent.
- **Action :** `@PreAuthorize("isAuthenticated()")` + 1 session Stripe Identity / 10 min / utilisateur. Le contrôleur doit aussi durcir `requireFirebaseUid()` sur le modèle de `AuthController`.

#### [HIGH] `PaymentService.createEscrow` ne plafonne pas le `amount` à 500 €
- **Fichier :** `PaymentService.java:219-238`
- **Statut :** ❌ NON CORRIGÉ AUTOMATIQUEMENT — fix de quelques lignes mais nécessite rejouer les bids existants pour vérifier.
- **Action :** avant `PaymentIntent.create`, ajouter
  ```java
  if (amount.compareTo(BigDecimal.valueOf(maxDeclaredValueEur)) > 0) {
      throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
              "amount-exceeds-cap", "Amount Exceeds Cap",
              "Le montant total dépasse la limite de " + maxDeclaredValueEur + " €");
  }
  ```

#### [HIGH] Absence totale de configuration CORS
- **Fichier :** `SecurityConfig.java`
- **Statut :** ❌ NON CORRIGÉ — risque de configuration permissive au niveau Nginx.
- **Action :** créer un `CorsConfigurationSource` bean restreint aux origines connues, `allowCredentials(false)`, et activer `http.cors(...)`.

#### [HIGH] `FcmTokenRequest` accepte une string sans validation
- **Fichier :** `auth/dto/FcmTokenRequest.java`
- **Statut :** ❌ NON CORRIGÉ — petit changement à valider.
- **Action :** `@NotBlank @Size(max = 512) @Pattern(regexp = "[A-Za-z0-9:_\\-]+")`.

### MEDIUM

| # | Fichier:ligne | Description courte | Statut |
|---|---|---|---|
| M-1 | `KycVerificationEntity.java:13` | Manque `@Where(deleted_at IS NULL)` | ✅ CORRIGÉ |
| M-2 | `V2__init_kyc_schema.sql:20` | `ON DELETE CASCADE` casse l'invariant soft-delete | ❌ ouvrir migration V37 `ON DELETE RESTRICT` |
| M-3 | `SecurityConfig.java:37` | CSRF désactivé sans assertion sur l'absence de `JSESSIONID` | ❌ ajouter test de non-régression |
| M-4 | `PaymentController.java:66` / `KycController.java:41` | Webhooks lisent le body avant signature → DoS possible | ❌ cap 1 MB + IP allowlist Stripe au Nginx |
| M-5 | `EncryptionService.java:30` | Dérivation AES via simple SHA-256 sans sel | ❌ exiger une clé base64 32 bytes brute |
| M-6 | `PaymentEntity.java` vs `V5` | `@Where deleted_at` mais V5 n'a pas la colonne (V19 oui) | ❌ migration test sur DB vierge |
| M-7 | `PaymentService.handleWebhook` | Pas de log IP source sur signature invalide | ❌ entrée `audit_log` `WEBHOOK_SIGNATURE_INVALID` |

### LOW

| # | Fichier:ligne | Description | Statut |
|---|---|---|---|
| L-1 | `SecurityConfig.java:50-52` | `/swagger-ui/**` et `/v3/api-docs/**` `permitAll` en prod | ❌ |
| L-2 | `AuthService.java:108-117` | Validation des rôles auto-assignables à durcir | ❌ |
| L-3 | `SecurityConfig.java:44` | `actuator/info` exposé | ❌ |
| L-4 | `UserService.deleteAccount` | Pas de purge Firestore conversations | ❌ |
| L-5 | `BidRejectedEventListener.java:84-90` et autres listeners refunds | `Refund.create` sans `setRefundApplicationFee(true)` ni `setReverseTransfer(true)` | ❌ |
| L-6 | `PaymentService.java:104,240` etc. | Pas d'idempotency keys Stripe | ❌ |

---

## 2. Backend — autres packages (matching, tracking, cancellation, disputes, admin, notifications, common)

### CRITICAL

#### [CRITICAL] Vérification KYC désactivée sur la création d'annonce et d'offre
- **Fichiers :** `AnnouncementService.java:154-162`, `BidService.java:58-65`
- **Statut :** ❌ NON CORRIGÉ AUTOMATIQUEMENT — toute la suite de tests existante crée des utilisateurs sans `kycStatus = VERIFIED`. Réactiver casserait la build, donc à faire en équipe avec une mise à jour des fixtures.
- **Action :**
  - Décommenter les blocs `if (user.getKycStatus() != KycStatus.VERIFIED) { throw new DonyBusinessException(HttpStatus.FORBIDDEN, "kyc-not-verified", ...); }`.
  - Ou (si vous voulez garder le bypass dev) : exposer la règle via un property flag `app.security.require-kyc: ${REQUIRE_KYC:true}` ; mettre `false` dans `application-dev.yml`/`application-test.yml`, `true` ailleurs.

### HIGH

| # | Fichier:ligne | Description | Statut |
|---|---|---|---|
| H-1 | `AddressController.java` + `GoogleAddressService.java` | Quotas in-memory réinitialisés au restart ; pas de `@Size` sur `query` | ❌ |
| H-2 | `tracking/search` (public) | Numéro de tracking 6 chars / 30-alphabet → ~7×10⁸ → enumerable, retour de corridor + payment status | ❌ rate-limit Nginx + entropie 10-12 chars |
| H-3 | `RecipientController.java:51-73,95-102` | Token public sans expiration → fuite = accès à vie aux photos | ❌ invalider `trackingToken` 30 j après `COMPLETED` |
| H-4 | `TrackingService.java:13-15,127,143-179` | Injection cross-package `PaymentRepository` | ❌ ArchUnit + DTO/event |
| H-5 | `CancellationService.java:165-176` | `findAll().stream()` sans LIMIT pour rematch | ❌ requête JPA dédiée |
| H-6 | `CancellationService.java:71-105` | Status `cancelTrip` n'autorisait que != `CANCELLED` | ✅ CORRIGÉ — exige `ACTIVE` |
| H-7 | `CancellationController.getRematchSuggestions` | Aucun check de propriété (IDOR) | ✅ CORRIGÉ — caller doit être sender / traveler / canceller |
| H-8 | `StorageController.uploadTrackingPhoto` | bidId arbitraire, pas de check, path-traversal possible (`..`) | ✅ CORRIGÉ — UUID + ownership + reject `..` |
| H-9 | `StorageController.uploadProfilePicture` | uid Firebase non sanitizé | ✅ CORRIGÉ — pattern `[A-Za-z0-9_-]{1,128}` |
| H-10 | `AuditLogRepository` | Méthodes `delete()` exposées (immutabilité au niveau trigger seulement) | ❌ interface custom sans deletes |

### MEDIUM

| # | Fichier:ligne | Description | Statut |
|---|---|---|---|
| MM-1 | `V4__tracking_events.sql` | Pas de trigger d'immutabilité (cf. `audit_log` V7) | ❌ V37 trigger `prevent_tracking_events_modification` |
| MM-2 | `AdminConversationController.java:38-44` | Renvoie `Page<ConversationEntity>` brute | ❌ DTO |
| MM-3 | `AdminConversationController.deleteMessage` | Path params non validés UUID | ❌ |
| MM-4 | `AdminConversationController` / `MessagingNotifyController` | `ResponseStatusException` au lieu de `DonyBusinessException` | ✅ CORRIGÉ dans MessagingNotifyController seulement |
| MM-5 | Tous les controllers matching/cancellation/notifications | `@PreAuthorize` manquant (ownership en service uniquement) | ❌ ajouter `hasRole('TRAVELER')`/`hasRole('SENDER')` partout |
| MM-6 | `BidController.rejectBid:82-89` | `@Valid` manquant | ✅ CORRIGÉ |
| MM-7 | `TrackingService.java:336-388` | `confirmation_code_expiry` jamais affecté à la création | ❌ 7 j d'expiration |
| MM-8 | `TrackingService.processScan` + `QrScanRequest` | `photoUrl` accepté arbitraire, attaché à la page publique recipient | ✅ CORRIGÉ — reject `http(s)://`, exige `tracking/{bidId}/...` |
| MM-9 | `MessagingNotifyController` | Comparaison non constant-time + silent failure | ✅ CORRIGÉ |
| MM-10 | `NoShowScheduler.java:67-89` | branche `announcement == null` laisse le bid en `NO_SHOW` sans event | ❌ |

### LOW

Voir `/tmp/dony-audit-findings/02-back-other.md` (rapport agent) pour le détail des 8 findings LOW non corrigés (`SmsService` log, message preview FCM, `confirmDelivery` sans GPS/photo, `BidService.resolveClientIp`, `NotificationController` class-level `@PreAuthorize`, `audit_log.actor_id` incohérent, RecipientController CSP, cache key collision).

---

## 3. Flutter — `dony_app/`

### CRITICAL

#### [CRITICAL] Release builds signés avec le keystore debug
- **Fichier :** `android/app/build.gradle.kts:53-55`
- **Statut :** ❌ NON CORRIGÉ AUTOMATIQUEMENT — un fix incorrect ferait casser la build release.
- **Action :** créer `android/key.properties` (gitignored), charger via Gradle, échouer si absent en release.

#### [CRITICAL] `env.dev.json` lu au build Gradle
- **Fichier :** `env.dev.json:7`, `android/app/build.gradle.kts:8-19`
- **Statut :** ❌ NON CORRIGÉ — restriction côté GCP Console à faire manuellement.
- **Action :** restreindre la clé Maps au SHA-1 release + package + bundle iOS ; passer la clé via env CI uniquement, pas via fichier JSON.

### HIGH

| # | Fichier:ligne | Description | Statut |
|---|---|---|---|
| F-1 | `local_auth_service.dart:17-24` | PIN stocké en clair dans `flutter_secure_storage` | ❌ Argon2id / PBKDF2-SHA256 + sel |
| F-2 | `kyc_webview_screen.dart:43-52` | `JavaScriptMode.unrestricted` + URL allow-list trop laxiste | ✅ CORRIGÉ — allow-list stricte (`verify.stripe.com`, `*.stripe.com`) |
| F-3 | `api_client.dart` | Aucune cert pinning Dio | ❌ ajouter `dio_certificate_pinning` ou adapter custom |
| F-4 | `main.dart:16` + manifest debug | Default `http://localhost...` ; pas de garde release | ✅ CORRIGÉ — assert non-debug `https://` + `pk_` |
| F-5 | `hive_service.dart:7-15` + `offline_sync_service.dart:35-44` | Hive non chiffré (GPS, bidId, photoPath) | ❌ `Hive.openBox(encryptionCipher: HiveAesCipher(key))` avec clé en `flutter_secure_storage` |
| F-6 | `offline_sync_service.dart:55-73` + `qr_scanner_screen.dart:620-680` | Photos tracking avec EXIF GPS jamais effacées après upload | ❌ `await File(photoPath).delete()` après upload |

### MEDIUM

| # | Fichier:ligne | Description | Statut |
|---|---|---|---|
| F-M1 | `notification_service.dart:99-104` | Risque de log token (debug only aujourd'hui) | ❌ lint custom |
| F-M2 | `bid_bloc.dart` (12 occurences), `announcement_bloc.dart` (5) | `emit(BidError(e.toString()))` → fuite URL/headers du `DioException` dans la SnackBar | ❌ utiliser `appException.message` (le `_AuthInterceptor` produit déjà l'`AppException` sanitisé) |
| F-M3 | `api_client.dart:22-26` | `LogInterceptor(requestBody=true, responseBody=true)` dump tokens en debug | ✅ CORRIGÉ — interceptor minimal (méthode + path + status) |
| F-M4 | `ios/Runner/Info.plist` | Manque `NSCameraUsageDescription`, `NSPhotoLibraryUsageDescription`, `NSPhotoLibraryAddUsageDescription`, `NSFaceIDUsageDescription`, `NSMicrophoneUsageDescription`, `NSAppTransportSecurity` | ❌ |
| F-M5 | `AndroidManifest.xml:8-11` | `android:allowBackup` par défaut `true` | ✅ CORRIGÉ — `false` |
| F-M6 | 23 fichiers | `setState` (interdit par CLAUDE.md) | ❌ chantier de fond |

### LOW

| # | Fichier:ligne | Description | Statut |
|---|---|---|---|
| F-L1 | PIN screens | PIN en `String _pin` exposé aux memory dumps + screenshots | ❌ FLAG_SECURE + reset après submit |
| F-L2 | `firebase_options.dart` | Clés Firebase publiques sans App Check | ❌ activer Play Integrity / DeviceCheck |
| F-L3 | `firestore_chat_repository.dart:144-159` | Increment client-side de `userMeta` | ❌ Cloud Function ou backend |
| F-L4 | `router.dart:91-97` | `state.extra as String` sans validation | ✅ CORRIGÉ — host `stripe.com` exigé |
| F-L5 | `main.dart:26,36-37` | Assert `pk_` au boot | ✅ CORRIGÉ |
| F-L6 | `chat_screen.dart:108-128` | Image messaging sans MIME/size cap | ❌ |
| F-L7 | Manifest | Manque `READ_MEDIA_IMAGES` Android 13+ | ✅ CORRIGÉ |
| F-L8 | Payment / KYC / PIN screens | Pas de `FLAG_SECURE` | ❌ |

### Déjà corrects côté Flutter
- Token Firebase ID jamais cached (toujours `getIdToken()` à la requête).
- `flutter_secure_storage` avec `encryptedSharedPreferences=true`.
- GoRouter exclusivement (aucun `Navigator.push`).
- Biometric gate avant l'API `/payments` (`payment_screen.dart:91`).
- Plafond 500 € côté formulaire (`create_bid_screen.dart:80`).

---

## 4. Cohérence dates & fuseaux horaires

> Règle visée : stockage UTC côté serveur, affichage dans le fuseau de l'utilisateur. Cible spéciale : trajets Paris/Lyon/Marseille (UTC+1/+2) → Dakar/Abidjan/Bamako (UTC+0) / Douala (UTC+1).

### CRITICAL

#### [CRITICAL D-1] Backend sérialise `LocalDateTime` sans offset, Flutter le parse en local
- **Symptôme :** un utilisateur à Paris (UTC+2 été) crée une remise à 14:00, le serveur stocke 12:00 UTC, le serveur renvoie `"2026-05-03T12:00:00"` (sans `Z`), Flutter `DateTime.parse()` retourne un DateTime **local** → l'utilisateur voit "12:00" (-2 h).
- **Statut :** ✅ CORRIGÉ
  - **Backend :** nouveau `JacksonConfig` qui enregistre un `LocalDateTimeSerializer` UTC. Tout `LocalDateTime` est désormais émis sous forme `2026-05-03T12:00:00Z`. Test associé : `JacksonConfigTest.localDateTime_isSerializedAsIsoWithUtcOffset`.
  - **Flutter (partiel) :** `firestore_chat_repository.dart` et `saved_trips_service.dart` font maintenant `.toUtc().toIso8601String()` ; `message_model._parseTs` traite tout timestamp serveur comme UTC.
- **Reste à faire côté Flutter :** introduire un helper `parseUtc(String)` au niveau `core/utils/` et l'utiliser dans tous les `fromJson` de modèles (`AnnouncementModel`, `BidModel`, `NotificationModel`, etc.) pour garantir `.toUtc()` puis `.toLocal()` à l'affichage. Une trentaine de fichiers à toucher.

#### [CRITICAL D-2] `RecipientController` formate des `LocalDateTime` UTC sans conversion
- **Fichier :** `RecipientController.java:28-29,65,98`
- **Statut :** ❌ NON CORRIGÉ AUTOMATIQUEMENT — modèle de fuseau du destinataire à définir (par défaut, le fuseau de la ville d'arrivée de l'annonce).
- **Action :**
  ```java
  ZoneId destZone = ZoneIdResolver.fromCity(announcement.getArrivalCity()); // table de mapping
  String formatted = e.getScannedAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(destZone).format(FMT);
  ```

### HIGH

| # | Fichier:ligne | Description | Statut |
|---|---|---|---|
| D-3 | `V23__bids_add_confirmation_code.sql:3`, `V33__conversations_per_user_delete.sql:5-6` | Colonnes `TIMESTAMP` (sans TZ) au lieu de `TIMESTAMP WITH TIME ZONE` | ✅ CORRIGÉ via `V36__align_timestamp_columns.sql` |
| D-4 | `EscrowScheduler.java:42`, `NoShowScheduler.java:45`, `MatchingScheduler.java:27` | `@Scheduled` sans `zone="UTC"` | ✅ CORRIGÉ pour les deux schedulers cron + suppression du doublon |
| D-5 | `BidScheduler.java:30` + `MatchingScheduler.java:27` | Doublon → 2 SMS H-2 par race | ✅ CORRIGÉ — `MatchingScheduler` supprimé |
| D-6 | `handover_screen.dart:34-65` | DST gap non-géré, pas de label TZ | ❌ |
| D-7 | `bid_detail_screen.dart:384-388` | Mix `DateTime.now()` (local) avec `handoverWindowStart` parsé | ❌ — devient correct une fois le helper `parseUtc` en place |
| D-8 | `NotificationDispatcher.java:37,109-115` | Body SMS/push formatté en UTC, lu comme heure FR | ❌ — convertir vers la zone de la corridor d'origine |

### MEDIUM

| # | Fichier:ligne | Description | Statut |
|---|---|---|---|
| D-M1 | `RatingService.java:79` | Convention "tout LocalDateTime est UTC" — fragile | ❌ migrer vers `Instant`/`OffsetDateTime` |
| D-M2 | `TrackingService.java:230-236` | Pas de tolérance d'horloge sur scan offline | ✅ CORRIGÉ — 5 min de tolérance |
| D-M3 | Multiples `chat_screen.dart` etc. | `DateFormat` sans `'fr'` explicite | ❌ |
| D-M4 | `message_model.dart:53-57` | Fallback `DateTime.now()` local | ✅ CORRIGÉ |
| D-M5 | `firestore_chat_repository.dart:35,54,76,103` | ISO local écrit dans Firestore | ✅ CORRIGÉ |
| D-M6 | `saved_trips_service.dart:48,66-67` | `departureDate.toIso8601String()` sans `.toUtc()` | ✅ CORRIGÉ |

### LOW

D-L1..D-L4 : voir `/tmp/dony-audit-findings/04-dates.md` (NotificationDispatcher locale, audit_log payload sans offset, schedulers convention-only).

---

## Fichiers modifiés

### Backend (commit `b89683d`)
```
src/main/java/com/dony/api/cancellation/CancellationController.java
src/main/java/com/dony/api/cancellation/CancellationService.java
src/main/java/com/dony/api/common/StorageController.java
src/main/java/com/dony/api/config/JacksonConfig.java       (nouveau)
src/main/java/com/dony/api/kyc/KycVerificationEntity.java
src/main/java/com/dony/api/matching/BidController.java
src/main/java/com/dony/api/matching/MatchingScheduler.java (supprimé)
src/main/java/com/dony/api/matching/NoShowScheduler.java
src/main/java/com/dony/api/messaging/MessagingNotifyController.java
src/main/java/com/dony/api/payments/EscrowScheduler.java
src/main/java/com/dony/api/tracking/TrackingService.java
src/main/resources/application-dev.yml
src/main/resources/application.yml
src/main/resources/db/migration/V36__align_timestamp_columns.sql (nouveau)
src/test/java/com/dony/api/cancellation/CancellationServiceTest.java
src/test/java/com/dony/api/config/JacksonConfigTest.java   (nouveau)
src/test/java/com/dony/api/messaging/MessagingNotifyControllerTest.java
src/test/resources/application-e2e.yml
src/test/resources/application-test.yml
```

### Flutter (commit `5e5da16`)
```
android/app/src/main/AndroidManifest.xml
lib/app/router.dart
lib/core/network/api_client.dart
lib/features/kyc/presentation/screens/kyc_webview_screen.dart
lib/features/matching/data/services/saved_trips_service.dart
lib/features/messaging/data/firestore_chat_repository.dart
lib/features/messaging/data/models/message_model.dart
lib/main.dart
```

---

## Tests

```bash
# Backend
cd dony-back && ./mvnw test
# → Tests run: 339, Failures: 0, Errors: 0, Skipped: 5  ✅
# Couverture JaCoCo : 70 % global (cible projet 90 % — non atteinte AVANT l'audit)

# Frontend
cd dony_app && flutter test
# → 1 005 tests / 4 échecs PRÉ-EXISTANTS (refactor map en cours, fichier
#   `search_announcement_screen_test.dart` lignes 747/797/814 — types Icon vs IconButton)
```

Tests ajoutés cette nuit :
- `JacksonConfigTest.localDateTime_isSerializedAsIsoWithUtcOffset` — vérifie le sérialiseur UTC.
- `CancellationServiceTest.getRematchSuggestions_noSuggestions_returnsEmpty` — mis à jour pour signer le nouveau check d'ownership (caller participant requis).
- `MessagingNotifyControllerTest.notify_returns401_whenSecret*` — adaptés à `DonyBusinessException`.

---

## Recommandations restantes (non auto-fixées)

### Bloquantes pour la prod

1. **Rotation Firebase service-account** + retrait du JSON.
2. **Définition `ENCRYPTION_KEY` prod** + audit qu'aucune donnée KYC n'a été chiffrée avec une clé "vraie" jusqu'ici.
3. **Activation KYC** dans `AnnouncementService` et `BidService`. Couplé à la mise à jour des fixtures de test.
4. **Chiffrement réel des colonnes KYC** via `EncryptedStringConverter` JPA.
5. **Keystore Android release** + remplacement du debug-signing.
6. **Restriction clé Google Maps** dans GCP Console.
7. **Cert pinning** Dio sur l'app Flutter pour les endpoints critiques (auth, payments, KYC).
8. **Hive encryption** pour la file offline (GPS / photo paths).
9. **Configuration CORS** explicite côté Spring.

### Importantes mais non bloquantes

10. Sanitization de `e.toString()` dans tous les Blocs Flutter (12+ occurrences) → utiliser `appException.message` produit par `_AuthInterceptor`.
11. Helper `parseUtc()` en Flutter + propagation dans tous les `fromJson` ; `.toLocal()` systématique avant `DateFormat.format()`.
12. `NotificationDispatcher.onHandoverDefined` — convertir vers le fuseau de la ville d'origine de la corridor.
13. `RecipientController` HTML — formater dans le fuseau de la ville d'arrivée + ajouter CSP.
14. `tracking_events` — trigger d'immutabilité + `@Immutable` JPA.
15. `@PreAuthorize` rôle systématique sur tous les controllers (`hasRole('SENDER')` / `hasRole('TRAVELER')`) en plus des checks d'ownership service.
16. iOS `Info.plist` — ajouter les `NSXxxUsageDescription` manquantes et `NSAppTransportSecurity`.
17. Migration `Instant` / `OffsetDateTime` au lieu de `LocalDateTime` côté entités/DTOs (rendre la convention UTC explicite par le type).
18. PIN local : Argon2id + sel + `FLAG_SECURE` sur les écrans sensibles.
19. Photos de tracking avec EXIF GPS — supprimer le fichier après upload.
20. Firebase App Check (Play Integrity / DeviceCheck) avant tout appel Firestore client-side.

### À planifier

- Mise en place d'un job CI gitleaks / trufflehog pour empêcher tout commit de secret futur.
- ArchUnit pour bloquer les imports cross-package interdits par CLAUDE.md (notamment `tracking → payments.*`).
- Audit régulier (recurring) — recommandation d'un agent `/schedule` mensuel.

---

*Rapport généré par Claude Code (Opus 4.7 1M) — 4 sous-agents parallèles + correctifs serialisés.*
