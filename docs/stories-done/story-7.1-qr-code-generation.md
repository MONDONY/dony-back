# Story 7.1 — Génération du QR code par transaction (Backend + Flutter)

**Date:** 2026-04-26
**Status:** ✅ Complète

## Résumé
Génération d'un QR code unique par transaction dès que le paiement passe en ESCROW. Le QR encode l'URL de scan de la transaction et est accessible via `GET /tracking/{bidId}/qr-code`. Côté Flutter, le QR s'affiche dans la vue détail du bid avec option de partage.

## Fichiers créés

**Backend:**
- `src/main/java/com/dony/api/tracking/TrackingService.java` — logique de génération QR (ZXing) + validation accès
- `src/main/java/com/dony/api/tracking/TrackingController.java` — endpoint `GET /tracking/{bidId}/qr-code`
- `src/main/java/com/dony/api/tracking/dto/QrCodeResponse.java` — record de réponse (bidId, scanUrl, qrCodeBase64)
- `src/main/java/com/dony/api/payments/events/PaymentEscrowReadyEvent.java` — event publié quand un paiement passe en ESCROW

**Flutter:**
- `lib/features/tracking/data/models/qr_code_model.dart` — modèle de données QR
- `lib/features/tracking/data/tracking_repository.dart` — appel API `GET /tracking/{bidId}/qr-code`
- `lib/features/tracking/bloc/tracking_event.dart` — `TrackingQrCodeRequested`
- `lib/features/tracking/bloc/tracking_state.dart` — `TrackingInitial`, `TrackingQrLoading`, `TrackingQrLoaded`, `TrackingQrError`
- `lib/features/tracking/bloc/tracking_bloc.dart` — bloc de chargement du QR
- `lib/features/tracking/presentation/widgets/qr_code_card.dart` — card QR avec image + instruction + bouton share

## Fichiers modifiés

**Backend:**
- `pom.xml` — ajout ZXing `core` + `javase` 3.5.3
- `src/main/resources/application.yml` — ajout `app.base-url`
- `src/main/java/com/dony/api/matching/BidService.java` — set `bid.qrToken = UUID` dans `acceptBid()`
- `src/main/java/com/dony/api/payments/PaymentService.java` — injection `ApplicationEventPublisher` + publication `PaymentEscrowReadyEvent` dans `handlePaymentEscrowActive()`

**Flutter:**
- `pubspec.yaml` — ajout `share_plus: ^10.0.0` et `path_provider: ^2.1.0`
- `lib/core/di/injection.dart` — enregistrement `TrackingRepository` + `TrackingBloc`
- `lib/features/matching/presentation/screens/bid_detail_screen.dart` — `MultiBlocProvider` + `QrCodeCard` dans le scroll quand `payment.status == 'ESCROW'`

## Comment ça fonctionne

### Vue d'ensemble du flux

1. Voyageur accepte le bid → `BidService.acceptBid()` → `bid.qrToken = UUID.randomUUID().toString()` (idempotent)
2. Expéditeur paie → Stripe déclenche webhook `payment_intent.amount_capturable_updated` → `PaymentService.handlePaymentEscrowActive()` → `payment.status = ESCROW` → publie `PaymentEscrowReadyEvent(bidId, paymentId)`
3. Expéditeur ouvre le détail de son bid → `_loadPaymentStatus()` charge le paiement → si `status == ESCROW` → `QrCodeCard` s'affiche
4. `QrCodeCard` publie `TrackingQrCodeRequested` dans `initState()` → `TrackingBloc` appelle `TrackingRepository.getQrCode(bidId)` → `GET /tracking/{bidId}/qr-code`
5. Backend : `TrackingService.getQrCode()` vérifie ownership (senderId), vérifie payment ESCROW, génère PNG ZXing 400×400 base64
6. Flutter : `Image.memory(base64Decode(b64))` affiche le QR
7. Bouton "Partager" → `share_plus` sauvegarde en tmp + ouvre le picker de partage natif

### Points d'entrée API

- `GET /api/v1/tracking/{bidId}/qr-code` — auth requise, expéditeur du bid uniquement (ESCROW ou RELEASED)
  - Réponse : `{ bidId, scanUrl, qrCodeBase64 }` (base64 = PNG 400×400)

### Entités JPA impliquées

- `BidEntity.qrToken` → colonne `qr_token VARCHAR(255) UNIQUE` dans V3 — set dans `acceptBid()`, lu par `TrackingService`
- `PaymentEntity.status` → doit être `ESCROW` ou `RELEASED` pour accéder au QR

### Logique métier critique

- **Idempotence qrToken** : `if (bid.getQrToken() == null)` avant set — en cas de retry HTTP 500, le token ne change pas
- **Guard payment** : le QR n'est accessible que si payment = ESCROW ou RELEASED (pas PENDING, FAILED, REFUNDED)
- **Pas de KYC requis** : accès au QR ne nécessite pas de KYC vérifié, juste d'être l'expéditeur du bid

### Events Spring publiés

- `PaymentEscrowReadyEvent(bidId, paymentId)` publié par `PaymentService` quand webhook Stripe confirme le fonds en escrow
  - Actuellement non écouté (réservé pour Stories 7.x futures comme génération de notification ou pré-allocation)

### Pièges et points d'attention

- **ZXing thread-safe** : `QRCodeWriter` est instancié à chaque appel (pas de singleton) car `BitMatrix.encode()` n'est pas thread-safe
- **Base64 sans préfixe data URI** : le backend retourne juste la base64 brute (pas `data:image/png;base64,...`). Flutter fait `base64Decode()` directement.
- **QrCodeCard dans initState** : le BLoC est fourni par `MultiBlocProvider` dans `BidDetailScreen` — si le widget est reconstruit, initState ne se re-déclenche pas (intention voulue — le QR ne change pas)
- **ESCROW uniquement** : si payment = RELEASED (livraison confirmée), l'expéditeur peut toujours voir le QR. Le voyageur a déjà été payé mais la preuve reste utile.

## Critères d'acceptation couverts

- [x] **Given** bid avec `payment.status = ESCROW` **When** `GET /tracking/{bidId}/qr-code` **Then** image PNG base64 retournée avec scanUrl
- [x] **Given** expéditeur dont paiement vient d'être confirmé **When** il consulte le détail **Then** QR visible et partageable avec instruction "Montrez ce QR au voyageur"

## Décisions techniques

- **ZXing côté backend vs QrFlutter côté Flutter** : généré côté backend pour garantir que l'URL de scan contient le bon `apiBaseUrl` (configurable par env) sans exposer la config dans le client
- **Base64 PNG au lieu de SVG** : compatibilité maximale avec `Image.memory()` Flutter et `Share.shareXFiles()`
- **ErrorCorrectionLevel.M** : bon équilibre entre densité QR et robustesse à la dégradation physique (impression, écran abîmé)
- **qrToken setté à l'acceptation du bid** (pas à l'ESCROW) : simplifie le service, évite une dépendance cross-package supplémentaire. Le token existe avant le paiement, mais le QR n'est pas accessible tant que payment ≠ ESCROW.
