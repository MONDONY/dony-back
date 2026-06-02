# Story — Commission Dony : wallet prioritaire, carte en fallback (Backend)

**Date:** 2026-06-02
**Status:** ✅ Complète

## Résumé
Pour les bids hors escrow (CASH, WAVE, ORANGE_MONEY), Dony ne prélève que sa commission (12 %) auprès du **voyageur**. Le prélèvement se fait désormais **sur le wallet en priorité**, et **sur la carte enregistrée en fallback** — au lieu d'exiger obligatoirement une carte. La capacité de prélèvement n'est plus vérifiée à la création d'annonce mais **à l'acceptation du bid**. Le routage des remboursements suit le canal réellement utilisé (`commission_charged_via`).

## Fichiers créés
- `payments/cash/CommissionChargedVia.java` — enum `{ WALLET, CARD }` : canal de prélèvement, sert au routage des remboursements.
- `payments/cash/CommissionSource.java` — enum `{ WALLET_FIRST, CARD }` : intention d'acceptation côté contrôleur.
- `payments/cash/CardCommissionTripCancelRefundListener.java` — sur `TripCancelledEvent`, rembourse via Stripe les commissions cartes (`via=CARD`).
- `src/main/resources/db/migration/V116__bids_commission_charged_via.sql` — colonne `bids.commission_charged_via` + backfill `CARD` (l'ancien flux ne prélevait que par carte).
- Tests : `CardCommissionTripCancelRefundListenerTest`, ré-écriture de `WalletCancellationListenerTest`, ajouts massifs dans `CashCommissionServiceTest`, `CashCommissionControllerTest`, `WalletServiceTest`.

## Fichiers modifiés
- `matching/BidEntity.java` — champ `commissionChargedVia` (`@Enumerated(STRING)`, colonne nullable).
- `payments/cash/CashCommissionService.java` — cœur de la feature (détails ci-dessous).
- `payments/cash/CashCommissionController.java` — `accept-with-commission?commissionSource=WALLET_FIRST|CARD` ; mapping `INSUFFICIENT_WALLET → 409`.
- `payments/cash/dto/AcceptanceStatusDto.java` — ajout `INSUFFICIENT_WALLET`.
- `payments/cash/dto/AcceptBidResponse.java` — champs `availableBalance, requiredCommission, hasCard` + factory `insufficientWallet(...)`.
- `payments/cash/CommissionRefundListener.java` — `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` ; route par `commissionChargedVia` (WALLET→wallet credit, CARD→Stripe refund) ; clé idempotence `wallet-refund-noshow-{bidId}`.
- `payments/cash/CashCommissionWebhookHandler.java` — pose `commissionChargedVia=CARD` sur succès PaymentIntent.
- `payments/wallet/WalletCancellationListener.java` — ne traite que `via=WALLET`, délègue à `refundCommissionToWallet` (clé `wallet-refund-cancel-{bidId}`).
- `payments/wallet/WalletService.java` — `debit` annoté `@Transactional(noRollbackFor = InsufficientWalletBalanceException.class)`.
- `payments/wallet/InsufficientWalletBalanceException.java` — champ `availableBalance` + getter.
- `payments/mobilemoney/MobileMoneyCommissionListener.java` — délègue à `chargeCommissionAuto`.
- `payments/BidAcceptedEventListener.java` — branche CASH (code mort) supprimée ; capture Stripe conservée.
- `matching/AnnouncementService.java` — `resolvePaymentMethods` ne lève plus `CommissionMethodMissingException` pour CASH.
- `cancellation/events/TripCancelledEvent.java` + `cancellation/CancellationService.java` — propagation du `bidCommissionChargedVia` par bid.

## Comment ça fonctionne (pour la maintenance)

### Flux 1 — Acceptation synchrone d'un bid cash (`acceptCashBid`)
1. `POST /bids/{bidId}/accept-with-commission?commissionSource=WALLET_FIRST` (défaut).
2. Validations : bid trouvé (verrou `findByIdForUpdate`), annonce trouvée & possédée par le voyageur, annonce accepte CASH, bid est CASH, capacité suffisante, idempotence (déjà ACCEPTED+CHARGED → 200).
3. **WALLET_FIRST** : si `walletService.getBalance ≥ commission` → `chargeCommissionFromWallet` (débit + `CHARGED` + `via=WALLET`) puis `finalizeBidAcceptance` → **200**. Sinon → `AcceptBidResponse.insufficientWallet(solde, commission, hasCard)` → **409**.
4. Race TOCTOU : si le solde a chuté entre `getBalance` et `debit`, `debit` lève `InsufficientWalletBalanceException` (sans rollback grâce à `noRollbackFor`) → on renvoie **409** avec le solde réel de l'exception (pas un 500).
5. **CARD** (`commissionSource=CARD`, retry explicite) : `chargeCommission` (PaymentIntent off-session). `succeeded` → `via=CARD` + finalize → 200 ; `requires_action` → **202** (3DS) ; échec → **422**.

### Flux 2 — Confirmation après 3DS (`confirmCommissionAcceptance`)
`POST /bids/{bidId}/confirm-acceptance`. Relit le PaymentIntent ; `succeeded` → `CHARGED` + `via=CARD` + finalize. Backfill : un bid déjà `CHARGED` via PI mais `via=null` (ancien flux) se voit poser `CARD`.

### Flux 3 — Mobile money asynchrone (`chargeCommissionAuto`)
Déclenché par `BidPaidByMobileMoneyEvent` via `MobileMoneyCommissionListener`. Wallet d'abord, carte ensuite. **Pas d'interaction utilisateur** : 3DS impossible → `FAILED` (créance). Toute erreur Stripe transitoire est **rattrapée** (`catch RuntimeException`) pour ne pas rollback la tx `REQUIRES_NEW` du listener (le paiement principal est déjà commité). Ni wallet ni carte → `FAILED` + audit `COMMISSION_AUTO_FAILED`.

### Points d'entrée API
- `POST /bids/{bidId}/accept-with-commission?commissionSource=…` — voyageur. 200/202/409/422.
- `POST /bids/{bidId}/confirm-acceptance` — voyageur, après 3DS. 200/422.

### Remboursements — routage par `commission_charged_via`
|              | SENDER_NO_SHOW (`CommissionRefundListener`) | Trip cancelled |
|--------------|---------------------------------------------|----------------|
| **WALLET**   | credit wallet (`wallet-refund-noshow-{bidId}`) | `WalletCancellationListener` → credit wallet (`wallet-refund-cancel-{bidId}`) |
| **CARD**     | Stripe refund (`refundCommission`)          | `CardCommissionTripCancelRefundListener` → Stripe refund |

Gardes `commissionStatus == CHARGED` partout (anti double-remboursement), clés d'idempotence distinctes par déclencheur.

### Events Spring
- Écoutés : `TripCancelledEvent` (porte `bidCommissionChargedVia`), `CancellationConfirmedEvent` (no-show), `BidPaidByMobileMoneyEvent`.
- Publiés : `BidAcceptedEvent` via `finalizeBidAcceptance`.

### Pièges et points d'attention
- **`noRollbackFor` sur `debit`** : sans lui, l'exception de solde marque la tx rollback-only → le `acceptCashBid` renverrait 500 au lieu de 409.
- **`@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW`** sur les listeners de remboursement : obligatoire (règle projet #18) car ils écrivent wallet/Stripe.
- **`chargeCommissionAuto` ne doit jamais propager d'exception** : sinon rollback du paiement MM déjà commité.
- **Idempotence wallet** : `chargeCommissionFromWallet` vérifie `existsByUserIdAndBidIdAndType(...COMMISSION_DEDUCTED)` car `WalletService.debit` n'est pas idempotent.
- **Backfill V116** correct uniquement pour l'état initial (toute commission CHARGED l'était par carte).

## Critères d'acceptation couverts
- [x] Wallet prélevé en priorité si solde ≥ commission (CASH + MM).
- [x] Carte en fallback ; à défaut des deux → refus (409) / créance (MM).
- [x] Voyageur sans carte mais wallet financé peut accepter un bid cash.
- [x] Capacité de paiement vérifiée à l'acceptation, plus à la création d'annonce.
- [x] Remboursements routés par canal sans trou ni double-remboursement.

## Tests
- `./mvnw test` → **1353 tests, 0 échec, 0 erreur**, 6 skipped.
- `./mvnw test jacoco:report` → couverture **instruction ≥ 90 %** sur toutes les classes touchées :
  `CashCommissionService` 97.8 %, `CashCommissionController` 94.7 %, `WalletService` 100 %, `WalletCancellationListener` 100 %, `MobileMoneyCommissionListener` 100 %, `CardCommissionTripCancelRefundListener` 94.4 %, `CommissionRefundListener` 92.4 %, `CashCommissionWebhookHandler` 95.0 %, `BidAcceptedEventListener` 95.1 %.
- Tests ajoutés/réécrits : `CashCommissionServiceTest` (63 tests — wallet-first, TOCTOU, MM auto, refund wallet, saveCommissionMethod, gardes acceptCashBid), `CashCommissionControllerTest` (16 — 409, param commissionSource, 401), `WalletCancellationListenerTest` (9 — routage + délégation), `WalletServiceTest` (getBalance/getTransactions/debit-create), `CardCommissionTripCancelRefundListenerTest`.

## Décisions techniques
- **Canal `commission_charged_via` persisté** plutôt que déduit : un bid peut être MM (donc carte) ou wallet, et la déduction a posteriori (présence d'un PI) est fragile → colonne explicite, source de vérité du routage.
- **Porte annonce relâchée** (décision utilisateur « autoriser toujours, vérifier à l'acceptation ») : le wallet existe toujours (`getOrCreate`), donc aucune raison de bloquer la création d'annonce cash.
- **MM = même logique wallet-first** (décision utilisateur), mais fallback carte **automatique** (pas de 3DS possible) et échec = créance, jamais d'exception remontée.
