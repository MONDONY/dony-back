# YadonyPaymentSheet — sheet de paiement custom — Design

## Contexte

L'app utilise la PaymentSheet préconstruite de Stripe (`initPaymentSheet` + `presentPaymentSheet`) sur 8 points d'appel. Le porteur produit veut **sa propre bottom sheet de paiement**, au design yadony, tout en gardant Stripe comme processeur. Maquettes validées : `https://claude.ai/code/artifact/803c4d74-0439-4481-b873-8df685c1a349` (v2).

Constat important relevé pendant l'analyse : la PaymentSheet actuelle n'est **pas** attachée à un customer Stripe (pas d'ephemeral key) — elle ne propose donc jamais les cartes déjà utilisées. La sheet custom apporte cette capacité en plus du design.

## Décisions produit (validées)

1. **Moyens de paiement, dans cet ordre** : Apple Pay (iOS) / Google Pay (Android) → PayPal → cartes enregistrées → nouvelle carte.
2. **Affichage conditionnel strict, aucun bouton mort** : wallet seulement si le device le supporte (`Stripe.instance.isPlatformPaySupported`), PayPal seulement si le PaymentIntent le déclare (lecture de `paymentMethodTypes` via `retrievePaymentIntent(clientSecret)` — déjà le cas : le backend ajoute `paypal` aux types).
3. **« Enregistrer cette carte » : activé par défaut** (désactivable d'un tap).
4. **Écran de succès dans la sheet** (pas de plein écran), avec le message d'escrow (« le voyageur sera payé après la remise du colis »).
5. **Déploiement pilote** : le composant remplace la sheet Stripe sur le **checkout de bid** (écran + bottom sheet) uniquement. Les 6 autres appels basculeront après validation visuelle sur device.
6. La **biométrie/PIN avant paiement** (`requirePaymentAuth`) reste inchangée, au même point du flux.

## Invariants non négociables

- **PCI** : la saisie carte passe exclusivement par le composant natif Stripe (`CardFormField`) embarqué dans la sheet. Jamais de `TextField` maison pour un numéro de carte. Conformité identique à aujourd'hui.
- **Backend escrow inchangé** : PaymentIntent `capture_method: manual`, commission, webhooks, `promoteBidOnPaymentAuthorized` — rien ne bouge.
- **Règle bottom sheet du projet** : le bouton payer vit dans `stickyBottom` de `YadonyBottomSheet`, jamais dans le `child` scrollable.
- 3DS : géré par `confirmPayment` (redirection auto), retour via le deep link existant `yadony://stripe/payment-return`.

## Architecture

### Backend (yadony-back) — deux ajouts, package `payments/`

1. **Customer attaché + sauvegarde de carte.** `PaymentService.createPaymentIntentForBid` (et le chemin checkout) :
   - garantit un customer Stripe (`ensureStripeCustomer(sender)` — réutiliser/extraire le pattern existant de `CashCommissionService:736`, le customer id est déjà persisté sur `UserEntity.stripeCustomerId`) et le pose sur le PaymentIntent (`setCustomer`).
   - nouveau champ optionnel `savePaymentMethod` (bool) sur la requête de checkout → si vrai, `setSetupFutureUsage(OFF_SESSION)`.
   - Attacher le customer est sans effet sur l'escrow ; ça rend simplement les cartes réutilisables.
2. **`GET /payments/me/payment-methods`** : liste les cartes du customer de l'utilisateur courant via l'API Stripe server-side (`PaymentMethod.list(customer, type=card)`). Réponse : `[{id, brand, last4, expMonth, expYear}]`. Vide si pas de customer. Jamais d'erreur bloquante (Stripe down → liste vide + log).

Pas d'ephemeral key nécessaire (c'était un besoin de la PaymentSheet Stripe, pas du flux custom).

### Flutter (yadony_app) — feature `payments/`, composant réutilisable

**`YadonyPaymentSheet.show(context, clientSecret, {amountLabel, contextLabel, onSuccess})`** — construit sur `YadonyBottomSheet` :

- **Résolution des moyens disponibles à l'ouverture** :
  - `retrievePaymentIntent(clientSecret)` → montant, devise, `paymentMethodTypes` (PayPal ou non) ;
  - `isPlatformPaySupported()` → wallet ou non ;
  - `GET /payments/me/payment-methods` → cartes enregistrées (échec réseau → liste vide, la sheet reste fonctionnelle).
- **Vue 1 (principale)** : bouton `PlatformPayButton` natif (Apple/Google Pay) → `confirmPlatformPayPayment` ; bouton PayPal (charte or officielle) → `confirmPayment(clientSecret, PaymentMethodParams.payPal(...))` ; liste des cartes enregistrées (sélection radio, `confirmPayment` avec `paymentMethodId`) ; ligne « Nouvelle carte » → vue 2. Bouton `Payer {montant}` en stickyBottom (BLoC : état enabled/loading).
- **Vue 2 (nouvelle carte)** : `CardFormField` natif stylé tokens yadony ; toggle « Enregistrer cette carte » (le flag est transmis AVANT la création du PaymentIntent côté checkout — voir note flux) ; même stickyBottom.
- **Vue 3 (succès)** : coche animée (300 ms, interruptible), récap montant + moyen, encart escrow, bouton de sortie (`onSuccess` navigue comme aujourd'hui).
- **Erreurs** : mêmes messages qu'aujourd'hui (annulation silencieuse si `FailureCode.Canceled`, sinon message localisé) ; en cas d'échec la sheet reste ouverte sur la vue courante, bouton ré-armé.
- **État** : un `PaymentSheetBloc` dédié (states: loading, ready, processing, success, failure) — pas de setState.

**Note flux « enregistrer la carte »** : le PaymentIntent est créé par le backend avant l'ouverture de la sheet. Le toggle doit donc être transmis au moment du checkout (`BidCheckoutRequest.savePaymentMethod`). Choix v1 : le checkout envoie `savePaymentMethod=true` par défaut (aligné sur le toggle par défaut) ; décocher le toggle dans la sheet appelle un endpoint léger `PATCH /payments/intents/{id}/save-payment-method {save:false}` qui met à jour `setup_future_usage` sur le PaymentIntent (l'API Stripe le permet tant que l'intent n'est pas confirmé). Ainsi le toggle reste dans la sheet, où l'utilisateur le comprend.

### Pilote (remplacement)

`create_bid_screen.dart` et `create_bid_bottom_sheet.dart` : `_presentPaymentSheet` remplace `initPaymentSheet`/`presentPaymentSheet` par `YadonyPaymentSheet.show(...)`. Le reste du flux (BidConfirmPaymentRequested, navigation `?from=payment`) est inchangé. Les 6 autres appels (wallet top-up, commission espèces, payment_screen, négociation ×2, recap) ne bougent pas dans ce chantier.

## Prérequis d'environnement (à la charge du porteur produit, non bloquants)

- Apple Pay : certificat marchand `merchant.app.yadony` provisionné dans App Store Connect + capability Xcode. Sinon le bouton n'apparaît pas (dégradation propre).
- Google Pay : activer dans la console Google Pay & Wallet. Idem dégradation.
- PayPal : activer le moyen de paiement dans le Dashboard Stripe (le backend le déclare déjà). Non activé → Stripe l'omet de `paymentMethodTypes` → le bouton n'apparaît pas.

## Tests

- **Backend** : unit `ensureStripeCustomer` (création si absent, réutilisation sinon) ; `savePaymentMethod` → `setup_future_usage` posé ; endpoint payment-methods (avec customer, sans customer, Stripe en erreur → liste vide) ; PATCH save-payment-method.
- **Flutter** : bloc (résolution des moyens : les 8 combinaisons wallet×paypal×cartes ; échec réseau payment-methods → sheet fonctionnelle) ; widget (vue 1 : boutons conditionnels, sélection carte ; vue 2 : toggle et bouton ; vue 3 : succès) ; le tout avec le SDK Stripe mocké derrière une interface (`PaymentGateway`) injectée — on ne teste pas Stripe, on teste notre logique.
- Test manuel device (cartes test Stripe : `4242…` succès, `4000 0027 6000 3184` 3DS, `4000 0000 0000 9995` déclinée).

## Hors scope

- Bascule des 6 autres points d'appel (après validation du pilote).
- Suppression d'une carte enregistrée depuis la sheet (v2 ; nécessite detach + UI).
- Autres moyens Stripe (Klarna, Link, virements).
