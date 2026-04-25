# Story 6.7 — Remboursement automatique en cas d'annulation (Backend)

**Date:** 2026-04-26
**Status:** ✅ Complète

## Résumé
Quand un voyageur annule son trajet, le `CancellationService` publie un `TripCancelledEvent` incluant les bid IDs affectés. Le `TripCancelledEventListener` dans `payments` écoute cet event et émet un remboursement Stripe pour chaque bid qui avait un paiement ESCROW.

## Fichiers créés
- `payments/TripCancelledEventListener.java` — `@EventListener @Async @Transactional`, boucle sur les bidIds

## Fichiers modifiés
- `cancellation/events/TripCancelledEvent.java` — ajout champ `List<UUID> affectedBidIds` + getter
- `cancellation/CancellationService.java` — collecte les bidIds dans la boucle et les passe à l'event
- `payments/PaymentService.java` — ajout handler webhook `charge.refunded` (idempotent)

## Comment ça fonctionne

### Vue d'ensemble du flux
1. `CancellationService.cancelTrip()` annule les bids et publie `TripCancelledEvent(announcementId, travelerId, affectedSenderIds, reason, affectedBidIds)`
2. `TripCancelledEventListener.handleTripCancelled()` reçoit l'event asynchronement
3. Pour chaque `bidId` dans `affectedBidIds` :
   - Cherche le payment — si absent, skip
   - Si `payment.status != ESCROW` : skip (PENDING = carte non débitée, RELEASED/REFUNDED/FAILED = rien à faire)
   - `Refund.create(RefundCreateParams.builder().setPaymentIntent(piId).build())`
   - `payment.status = REFUNDED`
   - Audit_log : `PAYMENT_REFUNDED`
   - `StripeException` → log ERROR, continue pour les autres bids
4. Webhook `charge.refunded` → `handleChargeRefunded()` — idempotent : si déjà REFUNDED, log info et skip ; sinon marque REFUNDED (filet de sécurité si listener a manqué le refund)

### Points d'entrée API
Aucun endpoint direct — déclenché uniquement par Spring Event depuis `cancellation`.

### Events Spring écoutés
- `TripCancelledEvent` publié par `cancellation/CancellationService` → écouté par `payments/TripCancelledEventListener`

### Pièges et points d'attention
- **Seuls les ESCROW sont remboursés** : un PENDING (carte non encore débitée/autorisée) ne nécessite pas de refund Stripe — le PaymentIntent expire automatiquement
- **Failures isolées** : si le refund d'un bid échoue, les autres bids sont quand même traités (try/catch individuel)
- **Double sécurité** : le webhook `charge.refunded` peut marquer REFUNDED si le listener a échoué
- **Pas de notification** ici — les notifs sender ("vous avez été remboursé") viendront de Epic 8

## Critères d'acceptation couverts
- [x] Given voyageur annule trajet avec bid ESCROW → Then refund Stripe + status=REFUNDED + audit_log
- [x] Given paiement PENDING au moment de l'annulation → Then skip (pas de refund nécessaire)
- [x] Given webhook charge.refunded → Then idempotence : si déjà REFUNDED, skip

## Décisions techniques
- **`TripCancelledEvent` étendu avec `affectedBidIds`** plutôt que de faire une requête bidRepository depuis `TripCancelledEventListener` — le listener ne doit pas injecter des repositories d'autres packages
- **Async listener** pour ne pas bloquer la transaction cancellation
- **ESCROW uniquement** : un paiement FAILED ou RELEASED ne se rembourse pas ici (cas gérés autrement)
