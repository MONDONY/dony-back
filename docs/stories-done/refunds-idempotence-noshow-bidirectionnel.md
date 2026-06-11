# Refunds idempotents + No-show bidirectionnel (Backend)

**Date:** 2026-06-11
**Status:** ✅ Complète
**Branche:** `feature/refund-processor` (base `fix/payment-event-listeners`)

## Résumé

Centralisation et idempotence des remboursements Stripe (`RefundProcessor` unique + clés d'idempotence), traitement correct du webhook `charge.refunded` (montants absolus, jamais d'écrasement d'un paiement déjà versé), et ajout du signalement **no-show bidirectionnel** (l'expéditeur signale un voyageur absent **et** le voyageur signale un expéditeur absent), valable CASH **et** Stripe.

## Fichiers créés

- `payments/RefundProcessor.java` — chemin unique de remboursement d'un paiement de bid (annulation PENDING / refund ESCROW), `@Transactional(REQUIRES_NEW)`, claim atomique + clé d'idempotence Stripe.
- `payments/SenderNoShowConfirmedListener.java` — écoute `CancellationConfirmedEvent(SENDER_NO_SHOW)` → rembourse l'escrow Stripe à l'expéditeur.
- `matching/NoShowService.java` — logique de marquage NO_SHOW d'un voyageur, extraite de `NoShowScheduler`, réutilisable (cron + signalement manuel), idempotente.
- `matching/TravelerNoShowReportListener.java` — écoute `TravelerNoShowReportedEvent` (publié par `cancellation/`) → délègue à `NoShowService`.
- `matching/events/BidMaterializedEvent.java` — event publié quand un bid est matérialisé après acceptation d'une négociation.
- `cancellation/events/TravelerNoShowReportedEvent.java` — event cross-package (cancellation → matching) du signalement « voyageur absent » par l'expéditeur.
- `requests/service/BidMaterializedListener.java` — écoute `BidMaterializedEvent` → écrit `materializedBidId` sur le thread de négociation.
- `db/migration/V131__payments_add_refunded_amount.sql` — colonne `payments.refunded_amount NUMERIC(10,2)`.
- `db/migration/V132__negotiation_threads_add_materialized_bid_id.sql` — colonne `negotiation_threads.materialized_bid_id UUID`.
- Tests : `RefundProcessorTest`, `SenderNoShowConfirmedListenerTest`, `NoShowServiceTest`, `TravelerNoShowReportListenerTest`, `BidMaterializedListenerTest`, `CancellationServiceNoShowTest` + scénario Cucumber dans `features/cancellation/annulation.feature`.

## Fichiers modifiés

- `payments/BidRejectedEventListener.java`, `ParcelRefusedEventListener.java`, `NoShowEventListener.java`, `BidExpiredOnDepartureEventListener.java` — délèguent désormais à `RefundProcessor` (suppression de la mécanique refund/cancel/audit dupliquée et du `@Transactional` local).
- `payments/TripCancelledEventListener.java` — traitement par lot avec transaction et claim **par paiement** via `RefundProcessor` (un échec n'annule plus les remboursements déjà réussis du lot).
- `payments/DeliveryEventListener.java` — clés d'idempotence Stripe `capture-{paymentId}` (capture legacy) et `transfer-{paymentId}` (Transfer v2).
- `payments/PaymentService.java` — `handleChargeRefunded` réécrit en machine à états sur montants absolus.
- `payments/PaymentEntity.java` — champ `refundedAmount`.
- `cancellation/CancellationService.java` — `reportSenderNoShow` ouvert aux bids Stripe (retrait du verrou CASH) ; nouvelle méthode `reportTravelerNoShow`.
- `cancellation/CancellationController.java` — endpoint `POST /cancellations/bids/{bidId}/report-traveler-noshow`.
- `matching/NoShowScheduler.java` — le cron délègue à `NoShowService.recordTravelerNoShow(bidId, "scheduler")`.
- `matching/ThreadAcceptedBidListener.java` — publie `BidMaterializedEvent` après matérialisation du bid.
- `requests/entity/NegotiationThreadEntity.java`, `requests/dto/NegotiationThreadResponse.java` — champ `materializedBidId`.
- `payments/PaymentListenerTransactionalContractTest.java` — sources scindées (`afterCommitListeners` pour les délégants, `fullContractListeners` pour les listeners porteurs de leur propre transaction) + assertion `REQUIRES_NEW` sur `RefundProcessor.processRefund`.
- `PaymentServiceTest.java` — test `handleChargeRefunded_alreadyRefunded_idempotent` aligné sur la nouvelle sémantique.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux — remboursement d'un paiement de bid

Tous les chemins de remboursement automatique d'un **escrow de bid** passent par `RefundProcessor.processRefund(paymentId, auditAction, auditActor, auditPayload)` :

1. Un event métier est publié (bid rejeté, colis refusé, no-show voyageur, bid expiré au départ, trajet annulé, sender no-show confirmé).
2. Le listener correspondant (`@TransactionalEventListener(AFTER_COMMIT)`) résout le `PaymentEntity` du bid et appelle `refundProcessor.processRefund(...)`.
3. `RefundProcessor` (transaction `REQUIRES_NEW` dédiée à ce paiement) agit selon le statut :
   - **PENDING** → `PaymentIntent.cancel()` (un PI non capturé ne se rembourse pas) → statut `REFUNDED`.
   - **ESCROW** → claim atomique SQL `markRefundedIfEscrow(paymentId)` (anti double-refund intra-instance) ; si 0 ligne, le paiement est déjà sorti d'ESCROW → no-op ; sinon `Refund.create(...)` avec `RequestOptions` clé d'idempotence `refund-{paymentId}` (déduplication côté Stripe, anti double-refund inter-instances).
   - **RELEASED / REFUNDED / FAILED / CANCELLED** → no-op (jamais de refund après versement).
4. Échec Stripe sur le refund ESCROW → `adminAlert.raise("STRIPE_REFUND_FAILED", ...)` + `IllegalStateException` : la transaction `REQUIRES_NEW` rollback le claim, le paiement reste remboursable au prochain passage.

### Vue d'ensemble du flux — no-show bidirectionnel

**Voyageur signale l'expéditeur absent (déjà existant, élargi à Stripe) :** `reportSenderNoShow` crée une `CancellationEntity` `PENDING_CONFIRMATION` (reason `SENDER_NO_SHOW`). À confirmation (`CancellationConfirmedEvent`) : `CommissionRefundListener` rembourse la commission (CASH) **et** `SenderNoShowConfirmedListener` rembourse l'escrow (Stripe) via `RefundProcessor`.

**Expéditeur signale le voyageur absent (nouveau) :**
1. `POST /cancellations/bids/{bidId}/report-traveler-noshow` (`@PreAuthorize("hasRole('SENDER')")`).
2. `CancellationService.reportTravelerNoShow(bidId, senderId)` vérifie : ownership (sinon 403), `status == ACCEPTED` (sinon `IllegalStateException`), `handoverWindowEnd` dépassé (sinon `IllegalStateException`) → audit `TRAVELER_NO_SHOW_REPORTED` → publie `TravelerNoShowReportedEvent`.
3. `matching/TravelerNoShowReportListener` (AFTER_COMMIT, REQUIRES_NEW) → `NoShowService.recordTravelerNoShow(bidId, "sender_report")`.
4. `NoShowService` (idempotent : no-op si le bid n'est plus `ACCEPTED`) marque le bid `NO_SHOW`, incrémente le compteur du voyageur, audit, publie `VoyageurNoShowEvent` (→ refund escrow + notification). Le même service sert le cron `NoShowScheduler` (source `"scheduler"`).

### Points d'entrée API

- `POST /cancellations/bids/{bidId}/report-traveler-noshow` — l'expéditeur signale le voyageur absent. Rôle `SENDER`, propriétaire du bid uniquement. Préconditions : bid `ACCEPTED` + fenêtre de remise dépassée.

### Entités JPA impliquées

- `PaymentEntity` → `payments` : nouveau `refunded_amount` (miroir absolu de `charge.amount_refunded`, EUR scale 2). Statut piloté par `markRefundedIfEscrow` (claim CAS) — ne jamais écraser un `RELEASED`.
- `NegotiationThreadEntity` → `negotiation_threads` : nouveau `materialized_bid_id` (référence lâche vers le bid, pas de FK — même style que `linked_negotiation_thread_id`).

### Logique métier critique

- **Idempotence webhook `charge.refunded`** : `amount_refunded` est **absolu et cumulé**. Le handler ré-enregistre toujours `refundedAmount` (rejouer le webhook réécrit la même valeur). `alreadyRecorded` est calculé via `compareTo` (insensible à l'échelle BigDecimal) **avant** la réécriture, et garde l'alerte/audit contre les doublons. Un refund **partiel** (`amount_refunded < amount`) trace le montant sans changer le statut. Un refund reçu sur un paiement **RELEASED** ne change jamais le statut et lève l'alerte `REFUND_AFTER_RELEASE` (réconciliation manuelle).
- **Double anti-refund** : claim DB atomique (intra-instance) **+** clé d'idempotence Stripe `refund-{paymentId}` (inter-instances). Les deux sont nécessaires.
- **No-show idempotent** : `recordTravelerNoShow` no-op si le bid n'est plus `ACCEPTED` — le cron et un signalement manuel concurrent ne peuvent pas marquer deux fois.

### Events Spring publiés / écoutés

- `BidRejectedEvent`, `ParcelRefusedEvent`, `VoyageurNoShowEvent`, `BidExpiredOnDepartureEvent`, `TripCancelledEvent` → écoutés par les listeners de `payments/` qui délèguent à `RefundProcessor`.
- `CancellationConfirmedEvent` (record, reason `CancellationReason`) → `SenderNoShowConfirmedListener` (refund escrow si `SENDER_NO_SHOW`).
- `TravelerNoShowReportedEvent` (cancellation → matching) → `TravelerNoShowReportListener` → `NoShowService`.
- `BidMaterializedEvent` (matching → requests) → `BidMaterializedListener` → écrit `materializedBidId`.

### Pièges et points d'attention

- **Règle critique #18** : tout listener de paiement doit être `@TransactionalEventListener(AFTER_COMMIT)` ; le `@Transactional(REQUIRES_NEW)` vit désormais sur `RefundProcessor.processRefund`. Le contrat est testé par `PaymentListenerTransactionalContractTest` — **ajouter tout nouveau listener de paiement à la bonne source** (`afterCommitListeners` si délégant, `fullContractListeners` sinon).
- **`@Transactional` interne ignoré** : `RefundProcessor` et `NoShowService` sont des beans séparés exprès (le proxy Spring est contourné si on appelle une méthode transactionnelle de la même classe). Ne pas re-internaliser.
- **AFTER_COMMIT sans transaction = event perdu** : tout `publishEvent` dont le listener est AFTER_COMMIT doit être dans une méthode `@Transactional` (cf. `triggerInProgressTransitions`).
- **Clés d'idempotence Stripe** : liées aux paramètres exacts de la requête — ne jamais réutiliser une clé avec des paramètres différents. `refund-{paymentId}` / `capture-{paymentId}` / `transfer-{paymentId}` sont stables par construction.
- **`PaymentEntity.getId()` est null hors persistance** : dans les tests unitaires, utiliser `spy(...)` + `doReturn(id).when(spy).getId()`.

## Critères d'acceptation couverts

- [x] Tous les remboursements escrow de bid passent par un chemin unique idempotent (`RefundProcessor`).
- [x] `charge.refunded` : montants absolus, refund partiel tracé, paiement `RELEASED` jamais écrasé + alerte de réconciliation.
- [x] Clés d'idempotence sur capture et Transfer à la livraison.
- [x] L'expéditeur peut signaler un voyageur absent (CASH + Stripe), bid `ACCEPTED` + fenêtre dépassée, masqué dès la remise.
- [x] Le sender-no-show rembourse l'escrow Stripe à confirmation.
- [x] Le détail du bid est accessible depuis une demande acceptée (`materializedBidId` exposé).
- [x] Audit_log créé pour chaque action significative (`PAYMENT_REFUNDED_*`, `REFUND_AFTER_RELEASE`, `PAYMENT_PARTIALLY_REFUNDED`, `TRAVELER_NO_SHOW_REPORTED`, `BID_NO_SHOW`).

## Tests

- `./mvnw test` → **1765 tests, 0 échec, 0 erreur, 7 skipped** (JUnit + Cucumber E2E inclus).
- Scénario Cucumber ajouté (`features/cancellation/annulation.feature`) : « l'expéditeur signale un voyageur absent après la fenêtre de remise → HTTP 200 et le bid passe NO_SHOW ».
- `./mvnw test jacoco:report` → classes nouvelles/modifiées focalisées ≥ 91 % : `RefundProcessor` 91 %, `DeliveryEventListener` 95 %, `NoShowService` 99 %, listeners 98–100 %, `BidMaterializedListener`/`TravelerNoShowReportListener`/`SenderNoShowConfirmedListener` 100 %.
- Note : la couverture **globale** de `PaymentService` (72 %) et `CancellationService` (85 %) reste sous le seuil à cause de code legacy non touché ; le code introduit/modifié par ce chantier est couvert (aucune régression de couverture sur le diff).

## Décisions techniques

- **Double protection anti-refund (claim DB + clé Stripe)** plutôt que l'une des deux : le claim protège intra-instance et permet le rollback transactionnel ; la clé Stripe protège inter-instances et déduplique côté API.
- **`compareTo` au lieu de `equals`** pour `alreadyRecorded` : `equals` sur `BigDecimal` est sensible à l'échelle (`10.0` ≠ `10.00`), ce qui casserait la détection de rejeu après relecture depuis `NUMERIC(10,2)`.
- **Garde null** sur `charge.getAmountRefunded()`/`getAmount()` : robustesse webhook (event partiel/malformé ignoré sans NPE).
- **`NoShowService` extrait en bean séparé** : réutilisation cron + manuel sans dupliquer la logique, et proxy transactionnel effectif (un appel intra-classe l'aurait court-circuité).
- **`materialized_bid_id` sans FK** : référence lâche cross-domaine (le bid vit dans `matching/`), cohérente avec le style existant `linked_negotiation_thread_id`.
- **Refund de commission CASH, force-refund admin J+48 et cleanup 3DS orphelins NON migrés vers `RefundProcessor`** : concerns distincts (chaîne de PaymentIntent séparée / override manuel / annulation d'auth non capturée), volontairement laissés isolés.
