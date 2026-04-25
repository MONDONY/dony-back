# Story 6.4 — Déblocage automatique escrow post-confirmation livraison (Backend)

**Date:** 2026-04-26
**Status:** ✅ Complète

## Résumé
Quand la livraison est confirmée, le package `tracking` publie un `DeliveryConfirmedEvent`. Le `DeliveryEventListener` dans `payments` écoute cet event, capture le PaymentIntent Stripe (`capture_method: manual`), et met le paiement en statut RELEASED.

## Fichiers créés
- `tracking/events/DeliveryConfirmedEvent.java` — event Spring portant uniquement le `bidId`
- `payments/DeliveryEventListener.java` — `@EventListener @Async @Transactional` qui capture l'escrow

## Comment ça fonctionne

### Vue d'ensemble du flux
1. `TrackingService` (Epic 7) publie `new DeliveryConfirmedEvent(bidId)` via `ApplicationEventPublisher`
2. `DeliveryEventListener.handleDeliveryConfirmed()` reçoit l'event asynchronement
3. Le listener cherche le payment par `bidId` — si absent ou statut ≠ ESCROW, il skip (idempotent)
4. Appel Stripe : `PaymentIntent.retrieve(piId)` puis `pi.capture()` → déclenche le virement vers le compte Express du voyageur
5. `payment.status = RELEASED`, `payment.escrowReleasedAt = now()`
6. Entrée `audit_log` : `action = ESCROW_RELEASED`
7. En cas de `StripeException` : log ERROR, pas de re-throw (le scheduler J+48 prend le relais)

### Points d'entrée API
Aucun endpoint direct — déclenché uniquement par Spring Event.

### Entités JPA impliquées
- `PaymentEntity` → table `payments` — champs `status` (VARCHAR), `escrow_released_at` (TIMESTAMP)

### Events Spring publiés / écoutés
- `DeliveryConfirmedEvent` publié par `tracking/TrackingService` → écouté par `payments/DeliveryEventListener`

### Pièges et points d'attention
- `@Async` requis : l'event est publié dans la transaction de `TrackingService`, ne pas bloquer
- `@Transactional` sur le listener pour que le save payment soit atomique
- Si la capture Stripe échoue, le payment reste ESCROW → le scheduler EscrowScheduler (6.5) créera une alerte J+48
- Le `DeliveryConfirmedEvent` ne contient que `bidId` (pas senderId/travelerId) pour garder le couplage minimal

## Critères d'acceptation couverts
- [x] Given livraison confirmée → When DeliveryConfirmedEvent publié → Then capture Stripe dans les 5s et status=RELEASED
- [x] Given capture Stripe réussie → Then escrowReleasedAt enregistré et audit_log ESCROW_RELEASED
- [x] Given échec capture Stripe → Then erreur loggée Sentry (niveau ERROR), pas de crash

## Décisions techniques
- **Async listener** : le `@Async` évite de bloquer la transaction tracking. Risque : le payment peut ne pas être trouvé si la transaction tracking n'est pas encore committée — acceptable car l'idempotence + le scheduler J+48 couvrent ce cas.
- **Pas de notification** au voyageur ici — les notifications FCM/SMS seront déclenchées par un `PaymentReleasedEvent` publié depuis `DeliveryEventListener` en Epic 8 (notifications).
