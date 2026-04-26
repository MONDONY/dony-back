# Story 8.1 — Configuration des canaux de notification Firebase FCM (Backend)

**Date:** 2026-04-26
**Status:** ✅ Complète

## Résumé
Infrastructure FCM complète : `FcmService` envoie des push via Firebase Admin SDK, `NotificationDispatcher` est le point central unique d'envoi, et l'endpoint `PUT /auth/me/fcm-token` stocke les tokens FCM des appareils Flutter.

## Fichiers créés
- `src/main/java/com/dony/api/notifications/NotificationDispatcher.java` — point d'entrée unique pour toutes les notifications sortantes
- `src/main/java/com/dony/api/auth/dto/FcmTokenRequest.java` — DTO pour l'enregistrement du token

## Fichiers modifiés
- `src/main/java/com/dony/api/notifications/FcmService.java` — remplacé le stub dev par l'implémentation Firebase Admin SDK complète
- `src/main/java/com/dony/api/auth/AuthController.java` — ajout `PUT /auth/me/fcm-token`
- `src/main/java/com/dony/api/auth/AuthService.java` — ajout `updateFcmToken()`
- `src/main/java/com/dony/api/tracking/TrackingService.java` — migré de `FcmService` direct vers `NotificationDispatcher`
- `src/test/java/com/dony/api/tracking/TrackingServiceTest.java` — mis à jour pour `NotificationDispatcher`
- `src/test/java/com/dony/api/payments/PaymentServiceTest.java` — corrigé l'erreur code (`bid-not-payable`)

## Comment ça fonctionne

### Flux token FCM
1. L'app Flutter appelle `FirebaseMessaging.instance.getToken()` au démarrage
2. Flutter fait `PUT /api/v1/auth/me/fcm-token` avec le token
3. `AuthService.updateFcmToken()` met à jour `UserEntity.fcmToken`
4. À chaque `onTokenRefresh` Firebase, Flutter renvoie le nouveau token
5. Si Firebase retourne `UNREGISTERED` lors d'un envoi, le token est effacé de la DB

### Flux envoi notification
1. Un service appelle `NotificationDispatcher.notifyUser(userId, title, body, data)`
2. `NotificationDispatcher` délègue à `FcmService.sendToUser(userId, ...)`
3. `FcmService` récupère le token FCM de `UserRepository`, construit le `Message` Firebase et l'envoie via `FirebaseMessaging.getInstance().send()`
4. Gestion des tokens expirés (`UNREGISTERED`) : token supprimé silencieusement

### Points d'entrée API
- `PUT /api/v1/auth/me/fcm-token` — enregistre/met à jour le token FCM de l'utilisateur connecté (auth requise)

### Règle d'architecture respectée
`TrackingService` injectait directement `FcmService` (violation de la règle cross-package). Migré vers `NotificationDispatcher` pour centraliser tous les appels de notification.

### Configuration Android
Deux canaux définis (côté Flutter) :
- `dony_general` — notifications générales
- `dony_transactional` — haute priorité (paiements, livraisons)

## Critères d'acceptation couverts
- [x] Token FCM stocké dans `UserEntity.fcmToken` à chaque connexion Flutter
- [x] Token mis à jour sur `onTokenRefresh`
- [x] Tokens `UNREGISTERED` supprimés automatiquement

## Tests
- `./mvnw test` → 187 tests, 0 rouge
- Tests mis à jour : `TrackingServiceTest`, `PaymentServiceTest`

## Décisions techniques
- **NotificationDispatcher vs appels directs** : toutes les features doivent passer par `NotificationDispatcher` — jamais `FcmService` directement depuis un autre package. Prépare le SMS fallback (Story 8.3) sans modifier les appelants.
- **SMS fallback timer** : non implémenté ici (Story 8.3). Le dispatcher a déjà `SmsService` injecté pour faciliter l'ajout.
- **`sendDataOnly` non réintroduit** : le scan DEPART envoie désormais une notification visible "Code de livraison disponible" au lieu d'un data-only push — meilleure UX.
