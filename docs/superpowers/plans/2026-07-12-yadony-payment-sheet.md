# YadonyPaymentSheet — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remplacer la PaymentSheet Stripe par une bottom sheet yadony custom (wallets + PayPal + cartes enregistrées + nouvelle carte), pilote sur le checkout de bid.

**Architecture:** Backend : customer Stripe attaché au PaymentIntent + `setup_future_usage` optionnel + endpoint de liste des cartes. Flutter : `PaymentGateway` (abstraction testable du SDK Stripe) + `PaymentSheetBloc` + `YadonyPaymentSheet` (3 vues sur `YadonyBottomSheet`). Escrow/commission/webhooks inchangés.

**Spec:** `docs/superpowers/specs/2026-07-12-yadony-payment-sheet-design.md` — les décisions produit et invariants y sont; la spec fait foi.

**Branches:** `feature/yadony-payment-sheet` (yadony-back, déjà créée) et `feature/yadony-payment-sheet` (yadony_app, à créer depuis main).

## Global Constraints

- **PCI : la saisie carte passe exclusivement par `CardFormField` natif Stripe.** Jamais de TextField maison pour un numéro de carte.
- **Escrow inchangé** : `capture_method: MANUAL`, commission, webhooks, `promoteBidOnPaymentAuthorized` — interdits de modification.
- **Bouton payer dans `stickyBottom`** de `YadonyBottomSheet` (règle CLAUDE.md), jamais dans le child scrollable.
- Aucun bouton mort : wallet si `isPlatformPaySupported`, PayPal si présent dans `paymentMethodTypes` du PaymentIntent, cartes si l'endpoint en renvoie.
- Pas de `setState` (BLoC), pas de `Navigator.push` (GoRouter).
- TDD strict. Le SDK Stripe est mocké derrière `PaymentGateway` — on teste notre logique, pas Stripe.
- Commits en français, jamais de `Co-Authored-By`.
- `requirePaymentAuth` (biométrie) reste appelé AVANT l'ouverture de la sheet, comme aujourd'hui.

---

### Task 1 (yadony-back): customer sur le PaymentIntent + savePaymentMethod

**Files:** Modify `payments/PaymentService.java` (~l.435, méthode de création du PI bid + chemin checkout), Modify `matching/dto/BidCheckoutRequest.java` (+`Boolean savePaymentMethod`), Create `payments/StripeCustomerService.java` (extraction du pattern `ensureStripeCustomer` de `CashCommissionService.java:736` — le customer id est persisté sur `UserEntity.stripeCustomerId`), Create `payments/dto/UpdateSavePaymentMethodRequest.java`, Modify le controller de paiements (PATCH `/payments/intents/{id}/save-payment-method`, ownership vérifié via metadata `sender_id`).
**Interfaces produites:** PI créé avec `.setCustomer(customerId)` toujours, `.setSetupFutureUsage(OFF_SESSION)` si `savePaymentMethod != false` (défaut true, aligné toggle). PATCH accepte `{save: boolean}`, met à jour le PI via l'API Stripe (autorisé tant que non confirmé), 404 si PI inconnu, 403 si pas au sender.
**Tests:** unit `StripeCustomerService` (créé si absent / réutilisé si présent / persisté), PI params (customer posé, setup_future_usage selon flag), PATCH (save=false retire, ownership, PI confirmé → 409).

### Task 2 (yadony-back): GET /payments/me/payment-methods

**Files:** Create `payments/dto/PaymentMethodResponse.java` (`id, brand, last4, expMonth, expYear`), Modify le controller de paiements.
**Comportement:** liste via `PaymentMethod.list(customer, type=card)` ; pas de customer → `[]` ; erreur Stripe → `[]` + log warn (jamais bloquant).
**Tests:** avec customer (mapping complet), sans customer, Stripe en erreur.

### Task 3 (yadony_app): PaymentGateway + PaymentSheetBloc

**Files:** Create `lib/features/payments/data/payment_gateway.dart` (interface + impl flutter_stripe : `retrievePaymentIntent`, `isPlatformPaySupported`, `confirmPlatformPay`, `confirmPayPal`, `confirmWithSavedCard(pmId)`, `confirmWithCardForm({save})`), Create `lib/features/payments/data/payment_methods_repository.dart` (GET payment-methods + PATCH save flag), Create `lib/features/payments/bloc/payment_sheet_bloc.dart` (+states/events).
**States:** `loading → ready(methods: {walletAvailable, paypalAvailable, savedCards[], amountLabel}) → processing(method) → success(method) | failure(message, retryable)`.
**Tests bloc:** 8 combinaisons de disponibilité ; échec réseau payment-methods → ready avec cartes vides ; annulation wallet/PayPal/carte → retour ready (pas failure) ; échec confirm → failure ré-armable ; succès → success.

### Task 4 (yadony_app): YadonyPaymentSheet UI (3 vues)

**Files:** Create `lib/features/payments/presentation/yadony_payment_sheet.dart` (+ sous-widgets), tests widget.
**Vues (maquette artifact v2 fait foi):** 1) PlatformPayButton natif → PayPal or officiel → cartes enregistrées (radio) → « Nouvelle carte » ; stickyBottom `Payer {montant}` + ligne biométrie. 2) `CardFormField` stylé tokens + mention « champ sécurisé Stripe » + toggle enregistrer (défaut ON, PATCH save=false si décoché). 3) succès : coche animée 300 ms interruptible, récap, encart escrow, bouton sortie. Erreur: sheet reste ouverte, snackbar comme aujourd'hui, `FailureCode.Canceled` silencieux.
**Wrapper/stickyBottom:** pattern `wrapper: (child) => BlocProvider(...)` + `BlocBuilder` dans stickyBottom (table CLAUDE.md).
**Tests widget:** boutons conditionnels (4 cas), sélection carte active le bouton, toggle OFF déclenche le PATCH, succès affiche l'encart escrow, erreur ré-arme.

### Task 5 (yadony_app): bascule pilote checkout bid

**Files:** Modify `matching/presentation/screens/create_bid_screen.dart` (`_presentPaymentSheet` → `YadonyPaymentSheet.show`), Modify `matching/presentation/widgets/create_bid_bottom_sheet.dart` (idem). `BidConfirmPaymentRequested` + navigation `?from=payment` inchangés (déclenchés par `onSuccess`).
**Tests:** adapter les tests existants des deux fichiers ; vérifier `requirePaymentAuth` toujours appelé avant.

### Vérification finale

- back: `./mvnw test` vert ; app: `flutter test` sans échec au-delà de la baseline main (79) ; `flutter analyze` propre.
- Test manuel device: cartes test Stripe (4242 succès, 4000 0027 6000 3184 3DS, 4000 0000 0000 9995 déclinée).
- PRs séparées back/app ; back mergée d'abord (le flag savePaymentMethod et l'endpoint doivent exister avant la bascule pilote).
