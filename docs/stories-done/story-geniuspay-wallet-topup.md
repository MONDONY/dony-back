# Story — Recharge du wallet voyageur via GeniusPay + retrait du mobile money comme paiement de bid (Backend)

**Date :** 2026-07-19 | **Status :** ✅ Complète (PR #117)

## Résumé

GeniusPay est un PSP marchand (mode direct : `payment_method` toujours fixé, jamais de checkout hébergé) — incompatible avec un paiement peer-to-peer direct expéditeur→voyageur. Cette feature le branche donc sur un flux qui lui correspond réellement : la **recharge du wallet interne du voyageur** (déjà existant, jusqu'ici un stub). En contrepartie, le mobile money est retiré comme mode de paiement direct d'un bid — l'expéditeur n'a plus que Cash ou Stripe ; le voyageur qui veut alimenter son wallet en Wave/Orange Money/MTN Money passe par GeniusPay.

**Spec :** `docs-claude/docs/superpowers/specs/2026-07-19-geniuspay-wallet-topup-design.md`
**Plan :** `docs-claude/docs/superpowers/plans/2026-07-19-geniuspay-wallet-topup-plan.md` (9 tasks)
**Branche :** `feature/geniuspay-integration`, basée sur `feature/currency-model` (PR #116, encore ouverte au moment du merge de cette PR — consomme `MinorUnits`/`MoneyRounding`/`CurrencyRegistry`/`PeggedFxRateProvider`/`CountryCurrencies` construits là-bas)

## Règle produit non négociable

GeniusPay ne traite **jamais** le prix du transport (sender→traveler). `CashCommissionService.chargeCommissionAuto` (prélèvement de la commission dony sur le wallet du voyageur) reste totalement inchangé — GeniusPay alimente uniquement le solde en amont.

## Fichiers créés

- `db/migration/V181__geniuspay_wallet_topup.sql` — tables `wallet_topup_requests` (17 colonnes : montant EUR + montant local gelé + taux + statut) et `processed_geniuspay_events` (anti-rejeu webhook, PK `external_reference`)
- `db/migration/V182__wallet_topup_requests_currency_varchar.sql` — `wallet_topup_requests.currency` `CHAR(3)`→`VARCHAR(3)` (bpchar cassait la validation de schéma Hibernate, même piège déjà rencontré 2× dans le repo)
- `payments/wallet/WalletTopupRequestEntity.java` + `WalletTopupRequestRepository.java` — persistance de chaque demande de recharge
- `payments/wallet/GeniusPayProperties.java` + `GeniusPayConfig.java` — config externe (`dony.geniuspay.*`), `RestTemplate` dédié qualifié `"geniusPayRestTemplate"`
- `payments/wallet/GeniusPayClient.java` + `GeniusPayPaymentResult.java` — client HTTP pur (mode direct), `POST /payments`, erreurs mappées en `DonyBusinessException` (502/504)
- `payments/wallet/GeniusPayCoverage.java` — mapping statique pays→rails réellement supportés par GeniusPay (SN/ML = Wave+Orange, CI/BF = les 3 rails)
- `payments/wallet/GeniusPaySignatureVerifier.java` — HMAC-SHA256 (même algorithme que `WaveGateway`), fail-closed si secret absent
- `payments/wallet/ProcessedGeniusPayEventEntity.java` + `ProcessedGeniusPayEventRepository.java` — anti-rejeu webhook
- `payments/wallet/GeniusPayWebhookController.java` — `POST /webhooks/genius-pay`, public (signature HMAC en garde)

## Fichiers modifiés

- `payments/wallet/WalletTopupOrchestrator.java` — `initiateWave`/`initiateOrangeMoney` (stubs, URL locale sans appel réseau) remplacés par une méthode unique `initiateMobileMoney` gérant WAVE/ORANGE_MONEY/MTN_MONEY, avec un vrai appel `GeniusPayClient`
- `payments/wallet/dto/WalletTopupRequest.java` — ajout `countryCode`/`phoneNumber` (nullable, `@Pattern`), `MTN_MONEY` comme valeur `paymentMethod` valide
- `matching/BidService.java` — rejet 422 unique `mobile-money-bid-payment-retired` pour `PaymentMethod.WAVE`/`ORANGE_MONEY`, avant toute autre logique métier
- `config/SecurityConfig.java` — `/webhooks/genius-pay` ajouté à `permitAll()`
- `application.yml` — bloc `dony.geniuspay.*` (clés API/secret webhook via env vars, jamais en dur)

## Comment ça fonctionne

### Flux recharge wallet (voyageur)

1. Le voyageur choisit un montant EUR + un mode (WAVE/ORANGE_MONEY/MTN_MONEY) + saisit pays et téléphone **à chaque recharge** (jamais déduit du profil — règle produit explicite).
2. `WalletTopupOrchestrator.initiateMobileMoney` :
   - vérifie `GeniusPayCoverage.supports(countryCode, provider)` → 422 si le rail n'est pas couvert pour ce pays,
   - résout la devise via `CountryCurrencies.forCountry(countryCode)` → 422 si hors zone connue,
   - **gèle** le montant local via `PeggedFxRateProvider.convert(...)` puis `MoneyRounding.roundTransactionalMinor(MinorUnits.toMinor(...), incrément)` — ce montant ne sera **jamais recalculé** après coup,
   - persiste `WalletTopupRequestEntity` (statut `PENDING`) **avant** l'appel réseau — une trace survit même si GeniusPay est injoignable,
   - appelle `GeniusPayClient.createPayment(...)`, puis met à jour l'entité avec la référence externe reçue.
3. Le voyageur est redirigé vers `payment_url` (retourné dans `WalletTopupResponse.redirectUrl`).
4. GeniusPay notifie le résultat via webhook → `GeniusPayWebhookController` crédite le wallet en **EUR d'origine** (jamais le montant local) via `WalletService.credit(...)`.

### Flux bid (expéditeur) — mobile money retiré

`BidService.createBid` rejette désormais `PaymentMethod.WAVE`/`ORANGE_MONEY` inconditionnellement, avant tout autre check (annonce acceptant ce mode, téléphone renseigné, etc. — devenus inatteignables). Seuls Cash et Stripe restent disponibles pour un nouveau bid. `MobileMoneyPaymentService`/`MobileMoneyPaymentEntity`/`MobileMoneyGateway`/`WaveGateway`/`OrangeMoneyGateway`/`MobileMoneyWebhookController` restent **intacts** — l'ancien flux mobile money bid-à-bid n'est pas supprimé, seulement rendu inaccessible pour un nouveau bid (compatibilité historique).

### Points d'entrée API

- `POST /wallet/topup` — inchangé côté route, gère désormais réellement WAVE/ORANGE_MONEY/MTN_MONEY (en plus de STRIPE existant)
- `POST /webhooks/genius-pay` — public (pas de token Firebase), sécurisé uniquement par vérification de signature HMAC-SHA256 (`X-GeniusPay-Signature`), **première instruction du handler**, avant tout parsing/traitement
- `POST /bids` (création) — WAVE/ORANGE_MONEY → 422 `mobile-money-bid-payment-retired`

### Entités JPA

- `WalletTopupRequestEntity` → `wallet_topup_requests` — `amountEur` (montant réellement crédité), `currency`/`amountMinor`/`fxRate`/`rateSource` (montant local gelé, informatif pour GeniusPay uniquement), `status` (PENDING/COMPLETED/FAILED/EXPIRED), `externalReference` (unique, lien webhook)
- `ProcessedGeniusPayEventEntity` → `processed_geniuspay_events` — pas de `BaseEntity` (référentiel technique sans soft delete), `@Id` = clé de dédup composite `event:reference` (voir Pièges)

### Logique métier critique

- **Le wallet reste EUR** — GeniusPay/XOF ne sert qu'à initier le paiement PSP, jamais au montant crédité.
- **Anti-double-crédit à 2 niveaux** : la clé de dédup webhook (composite `event:reference`) + `WalletService.credit` a sa propre idempotence indépendante sur `idempotencyKey` (`"geniuspay-" + reference`) — même si l'une des deux protections avait une faille, l'autre empêche un double crédit réel.
- **Garde de statut en défense en profondeur** : avant de créditer, le webhook vérifie que le `WalletTopupRequestEntity` est encore `PENDING` — un topup déjà `COMPLETED` n'est jamais recrédité, quelle que soit la cause d'un éventuel doublon.

## Pièges et points d'attention

- **`ProcessedGeniusPayEventEntity.externalReference` ne contient PAS une référence pure** — c'est une clé composite `event + ":" + reference` (ex. `"payment.success:MTX-A1B2"`), documentée par commentaire dans le code. Nécessaire car la référence GeniusPay est stable pour toute la durée de vie d'une transaction : si GeniusPay envoie un événement non-terminal (`payment.pending`) avant `payment.success` pour la même référence, une clé de dédup basée sur la référence seule aurait bloqué silencieusement le crédit du `payment.success` (perte d'argent silencieuse, réponse 200). Un futur lecteur qui requête cette table à la main doit le savoir.
- **`ProcessedGeniusPayEventEntity` implémente `Persistable<String>`** (`isNew()` toujours `true`) — nécessaire car son `@Id` est assigné manuellement (pas de `@GeneratedValue`) : sans ça, Spring Data JPA appelle `merge()` (UPDATE silencieux) au lieu de `persist()` (INSERT), et le rejeu webhook ne lève jamais d'erreur d'unicité.
- **Le webhook utilise `existsById(...)` check-then-act, pas insert-puis-catch** — un essai initial avec `saveAndFlush` + catch de `DataIntegrityViolationException` **dans la même `@Transactional`** provoquait un `UnexpectedRollbackException` (500 au lieu du 200 no-op attendu) sur un vrai rejeu, car Hibernate marque la transaction rollback-only dès le flush échoué. Le pattern `existsById` (aligné sur `StripeWebhookIngestService` déjà existant) évite ce piège — accepté comme non parfaitement atomique contre une race concurrente extrême, mais couvert par la contrainte PK unique + la 2e barrière `WalletService.credit`.
- **`WalletTopupOrchestratorTest` : la branche `unsupported-topup-country` (`CountryCurrencies.forCountry` vide) est structurellement inatteignable** — tout pays couvert par `GeniusPayCoverage` (SN/CI/ML/BF) est aussi dans la map CFA de `CountryCurrencies`. Gardé en défense en profondeur, jamais exercé en pratique.
- **`GeniusPayClient` : le cast `(Map) data` sur la réponse GeniusPay n'est pas protégé** contre un type inattendu (ex. GeniusPay renverrait une liste) — `ClassCastException` non catchée dans ce cas limite, remonterait brute. Non couvert par un test dédié, à surveiller si un jour la réponse réelle de l'API diverge du format documenté.
- **Contrat webhook GeniusPay non vérifié en conditions réelles** — le format exact du payload (`data.transaction.reference`, valeurs d'`event`) vient de la documentation API lue une fois, jamais confronté à un vrai payload GeniusPay. Si le format réel diverge, `reference == null` → no-op silencieux 200 → wallet jamais crédité, sans erreur visible. À valider avec un vrai payload capturé dès que possible (recommandation de la revue finale).

## Critères d'acceptation couverts

- [x] Recharge wallet voyageur en Wave/Orange Money/MTN Money via un vrai appel réseau GeniusPay (mode direct)
- [x] Montant local gelé avant l'appel PSP, jamais recalculé après coup
- [x] Pays/devise saisis à chaque recharge, jamais déduits du profil
- [x] Couverture pays/rail vérifiée avant tout appel réseau (422 propre si non couverte)
- [x] Webhook sécurisé par signature HMAC-SHA256, anti-rejeu, crédit wallet en EUR
- [x] Mobile money retiré comme paiement direct de bid (422 unique), `PaymentMethod` enum et flux mobile money historique intacts
- [x] Aucune modification de `MobileMoneyPaymentService`/`MobileMoneyPaymentEntity`/`MobileMoneyGateway`/`WaveGateway`/`OrangeMoneyGateway`/`MobileMoneyWebhookController`
- [x] Aucune migration existante modifiée (V181 + V182 additives)

## Tests

- `./mvnw test` → 0 rouge sur la suite complète (1678+ tests)
- Tests ajoutés : `WalletTopupRequestEntityTest` (profil e2e, Postgres embarqué réel — valide que V181 s'applique), `GeniusPayClientTest` (4 cas, TDD), `WalletTopupRequestValidationTest` (4 cas), `GeniusPayCoverageTest` (6 cas), `WalletTopupOrchestratorTest` (5 cas, montant gelé vérifié numériquement), `GeniusPaySignatureVerifierTest` (4 cas), `GeniusPayWebhookControllerTest` (6 cas après fix final, dont dédup composite et garde de statut)
- Tests modifiés : 3 dans `BidServiceTest` (comportement retiré → nouveau rejet unique), `WalletControllerIT` (2 tests pré-existants, régression réelle trouvée en vérification finale — `countryCode`/`phoneNumber` désormais requis + `GeniusPayClient` à mocker + seed devises manquant)
- Revue finale whole-branch (opus, 2 passages) : Ready to merge = Yes. 2 bugs financiers réels trouvés et corrigés en cours d'exécution (garde anti-rejeu inerte, transaction empoisonnée sur rejeu, clé de dédup trop large) — voir Pièges ci-dessus.

## Décisions techniques

| Décision | Choix | Alternatives écartées | Raison |
|---|---|---|---|
| Portée GeniusPay | Recharge wallet voyageur uniquement, jamais le paiement bid direct | Brancher GeniusPay sur le paiement expéditeur→voyageur existant | GeniusPay est un PSP marchand (mode direct fixe), incompatible avec un flux P2P sender-pays-traveler ; le wallet est le seul point où dony est réellement le marchand |
| Mode d'intégration | Direct (`payment_method` fixé) uniquement | Checkout hébergé | Cohérent avec une recharge initiée et confirmée dans l'app, sans redirection vers une page tierce de choix de moyen de paiement |
| `PaymentMethod.WAVE`/`ORANGE_MONEY` (enum bid) | Conservés, rejetés à la création | Supprimer les valeurs de l'enum | Compatibilité historique — des bids existants en base référencent encore ces valeurs |
| Anti-rejeu webhook | Clé composite `event:reference` dans la colonne `external_reference` existante | Migration pour ajouter une colonne `event` dédiée | Aucun changement de schéma nécessaire, VARCHAR(255) accueille la chaîne composite sans risque ; documenté par commentaire pour la lisibilité future |
| Pattern anti-rejeu | `existsById` check-then-act (comme `StripeWebhookIngestService`) | Insert-first + catch `DataIntegrityViolationException` dans la même transaction | La 2e approche marque la transaction rollback-only sur violation de contrainte → `UnexpectedRollbackException` (500) au lieu d'un 200 no-op propre sur un vrai rejeu |
| Branche de base | `feature/currency-model` (PR #116), pas `main` | Attendre que #116 soit mergée avant de commencer | Task 6 dépend directement des classes `Money`/`MinorUnits`/`CurrencyRegistry` construites dans #116, encore ouverte ; rebaser plutôt qu'attendre a permis de continuer sans bloquer sur le planning d'une autre PR |
