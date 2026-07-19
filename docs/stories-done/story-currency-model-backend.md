# Story — Modèle multi-devise : socle, migration des conversions, correctifs mobile money (Backend)

**Date :** 2026-07-19
**Status :** ✅ Complète (PR #116)

## Résumé

dony traitait l'euro comme une devise implicite partout dans le code de paiement : aucune colonne `currency` sur `payments`, ~25 sites qui convertissaient montant ↔ cents via un `×100`/`÷100` codé en dur (correct tant que tout était EUR, dangereux dès qu'une devise sans décimales comme le franc CFA entre en jeu). Le flux mobile money (Wave/Orange Money, stub) contenait en plus **trois bugs financiers réels** : montant chargé = valeur déclarée du colis (assurance) au lieu du prix du transport, devise codée en dur `"XOF"` même pour le Cameroun (XAF), et aucun gel du montant local à l'initiation.

Cette story construit le socle multi-devise (`common/money/`), migre l'intégralité des sites de conversion existants vers ce socle, ajoute le suivi du règlement réel (ce qu'un PSP a effectivement capturé, distinct du montant contractuel EUR), corrige les trois bugs mobile money, et ajoute la visibilité admin sur les commissions dont le prélèvement a échoué.

**Spec :** `docs-claude/docs/specs/2026-07-18-modele-devise-design.md` (v3, passée par deux tours d'audit adversarial avant implémentation)
**Plan d'implémentation :** `docs-claude/docs/superpowers/plans/2026-07-18-modele-devise-plan.md` (13 tasks backend, exécutées en subagent-driven-development avec revue individuelle de chaque task + revue finale whole-branch)

## Fichiers créés

### `common/money/` — socle devise

- `CurrencyEntity.java` / `CurrencyRepository.java` — table de référence `currencies` (code ISO, minor_unit, symbole, parité EUR, incrément d'arrondi), pas de `BaseEntity` (référentiel sans soft-delete, clé naturelle = code ISO)
- `CurrencyRegistry.java` — accesseur mis en cache Caffeine (TTL 1h) : `minorUnitOf`, `pegRateOf`, `roundingIncrementOf`, `enabledCurrencies`. Devise inconnue → `DonyBusinessException`
- `Money.java` — record `(BigDecimal amount, String currencyCode)`
- `MinorUnits.java` — **seul point de conversion** montant ↔ unités mineures de tout le codebase. `toMinor` (HALF_UP, affichage/indicatif), `toMinorExact` (lève si précision résiduelle, préserve le fail-fast des chemins qui déplacent de l'argent réel), `fromMinor` (sens retour)
- `FxRateProvider.java` (interface) / `PeggedFxRateProvider.java` — conversion via parité fixe en base (1 EUR = 655,957 XOF/XAF). Retourne `Optional.empty()` pour une devise non arrimée — jamais de taux inventé
- `MoneyRounding.java` — deux arrondis distincts : `roundTransactionalMinor` (débit réel, incrément local ex. 5 F CFA, plancher — jamais 0 pour un dû positif) et `roundRefundMinor` (remboursement, toujours arrondi supérieur, en faveur de l'utilisateur)
- `CountryCurrencies.java` — mapping statique pays ISO2 → devise CFA (UEMOA → XOF, CEMAC → XAF)
- `CurrencyController.java` / `dto/CurrencyResponse.java` — `GET /config/currencies` (public, couvert par le `permitAll` existant sur `/config/**`)

### Migrations

- `V176__currencies.sql` — table `currencies` + seed EUR/XOF/XAF
- `V177__payments_settlement.sql` — 4 colonnes settlement sur `payments` + backfill EUR sur toutes les lignes existantes (y compris soft-deleted, historique comptable complet)
- `V178__mobile_money_currency_freeze.sql` — 4 colonnes (`amount_minor`, `fx_rate`, `rate_source`, `settled_amount_minor`) sur `mobile_money_payments`
- `V179__payments_settlement_fx_rate_constraint.sql` — corrige la contrainte `chk_settlement_all_or_none` de V177 (oubliait `settlement_fx_rate`)
- `V180__mobile_money_currency_drop_default.sql` — retire le défaut `'XOF'` hérité de V112, devenu un piège latent depuis que l'application détermine toujours la devise explicitement

### Admin

- `AdminCommissionDebtController.java` / `dto/CommissionDebtResponse.java` — `GET /admin/commission-debts` (`@PreAuthorize("hasRole('ADMIN')")`), liste les bids en échec de prélèvement commission avec le montant dû calculé sur le **taux figé au moment de l'échec**, pas un taux recalculé en direct

## Fichiers modifiés

### Migration des conversions (sens sortant EUR→cents, `toMinorExact`/`toMinor` selon le comportement d'origine)

- `payments/PaymentService.java` — 2 sites (amount/commission escrow), + écriture atomique du règlement à la capture
- `payments/PriceBreakdown.java` — suppression de `grossCents()`/`commissionCents()`, conversion déplacée chez l'appelant
- `payments/DeliveryEventListener.java`, `payments/cash/CashCommissionService.java` (2 sites), `payments/NegotiationEscrowAdapter.java`, `admin/AdminPaymentController.java` — migrés
- `matching/BidService.java` — exclusion documentée (`rate×100` = pourcentage d'affichage, pas un montant)
- `payments/wallet/WalletTopupOrchestrator.java` — migré + `WalletTopupRequest` gagne `@Digits(integer=8, fraction=2)` (changement de comportement assumé : un montant à 3 décimales était silencieusement tronqué, il est maintenant rejeté 422 avant d'atteindre l'orchestrateur)
- `admin/dto/AdminPaymentDetailResponse.java`, `admin/dto/AdminPaymentListItemResponse.java`, `matching/ProAnalyticsService.java` — migrés vers `toMinor` (affichage)

### Migration des conversions (sens entrant cents→EUR, `fromMinor`)

- `payments/PaymentStripeWebhookHandler.java`, `payments/PaymentService.java` (refund tracking), `payments/wallet/ReferralRewardWalletListener.java`
- `referral/UserCreditEntity.java`, `referral/ReferralInvitationEntity.java`, `disputes/DisputeEntity.java` — commentaire documentant les colonnes cents natives EUR-implicites (non migrées, hors périmètre)

### Écriture du règlement (Task 11)

- `payments/PaymentRepository.java` — `markCapturedIfEscrow` étendu pour poser `captured_at` **et** les 4 colonnes settlement dans le **même** `UPDATE` JPQL atomique (le montant est précalculé côté Java et passé en paramètre, JPQL ne peut pas appeler `MinorUnits`)
- `payments/BidAcceptedEventListener.java`, `payments/NegotiationCaptureListener.java` — calculent et passent les 4 paramètres settlement
- `payments/mobilemoney/MobileMoneyPaymentService.java` — webhook confirmé pose `settledAmountMinor`
- `payments/PaymentEntity.java` — **`@DynamicUpdate`** (voir Pièges ci-dessous — corrige un clobber silencieux du règlement, et referme au passage un bug préexistant de contournement de garde anti-double-capture)
- `admin/AdminPaymentController.java` — `forceRelease()` comble aussi les colonnes settlement quand la capture se produit en dehors du flux normal (garde `settlementCurrency == null`, no-op si déjà posé)

### Correctifs mobile money (Task 12 — les 3 bugs originaux)

- `payments/cash/CashCommissionService.java` — extraction publique de `computeBidNet` (kgNet + gridNet, logique existante déplacée hors de `computeBidCommission`, comportement inchangé)
- `payments/mobilemoney/MobileMoneyPaymentService.java` — `initiate()` : montant = `computeBidNet` (plus `declaredValueEur`) ; devise = `CountryCurrencies.forCountry(countryCode)` (plus `"XOF"` en dur), erreur 422 propre si pays non couvert ; montant local **gelé** à l'initiation via `PeggedFxRateProvider.convert` + `MoneyRounding.roundTransactionalMinor`, erreur 422 propre si devise non convertible ; entrée `audit_log` `AMOUNT_FROZEN`

## Comment ça fonctionne

### Séparation devise contractuelle / devise de règlement

Le principe directeur : **EUR reste toujours la devise contractuelle** (prix, commission, calculs). La devise de règlement (ce qu'un PSP a réellement débité) est un fait constaté a posteriori, jamais une hypothèse. Aucune colonne monétaire existante (`amount`, `commission_amount`, etc.) n'a été touchée — tout est additif.

### Flux Stripe

1. Un paiement est créé en EUR (`amount`, `commission_amount` inchangés)
2. À la capture (`markCapturedIfEscrow`), les 4 colonnes settlement sont posées **atomiquement** avec `captured_at`, dans le même `UPDATE` — `settlement_currency='EUR'`, `settlement_amount_minor` (calculé via `MinorUnits.toMinorExact`), `settlement_fx_rate=1`, `settlement_rate_source='NONE'`
3. Si la capture se produit en dehors du flux normal (force-release admin sur un PI encore `requires_capture`), le contrôleur comble les mêmes colonnes après coup, avec une garde qui ne touche jamais un règlement déjà posé

### Flux mobile money

1. `initiate()` calcule le net du bid, résout la devise du wallet depuis le pays, convertit et arrondit (incrément transactionnel, ex. multiple de 5 F CFA) — **ce montant est gelé** dans `amount_minor` avant que le lien de paiement soit généré
2. Le webhook de confirmation pose `settled_amount_minor` (aujourd'hui = le montant gelé, car la gateway est un stub qui ne rapporte pas encore de montant PSP réel — à remplacer par le vrai montant rapporté quand un PSP réel sera branché)

### Points d'entrée API

- `GET /config/currencies` — public, référentiel des devises actives (indicatif uniquement, jamais transactionnel)
- `GET /admin/commission-debts` — `ROLE_ADMIN`, créances de commission en échec avec montant figé au moment de l'échec

### Entités JPA impliquées

- `CurrencyEntity` → table `currencies` (référentiel, pas de soft-delete)
- `PaymentEntity` → 4 nouvelles colonnes `settlement_*` + `@DynamicUpdate`
- `MobileMoneyPaymentEntity` → 4 nouvelles colonnes (`amount_minor`, `fx_rate`, `rate_source`, `settled_amount_minor`)

### Logique métier critique

- **`MinorUnits` est le seul chokepoint** de conversion montant ↔ cents dans tout le module paiements — aucun `×100`/`÷100` codé en dur ne doit être réintroduit ailleurs
- **Deux arrondis distincts, jamais mélangés** : indicatif (incrément 1, affichage) vs transactionnel (incrément de la devise, débit réel, avec plancher)
- **`markCapturedIfEscrow` est un `UPDATE` JPQL en bulk**, pas une simple sauvegarde d'entité — toute écriture supplémentaire sur une entité chargée avant cet appel doit passer par le même mécanisme atomique ou risque d'écraser ce qu'il vient de poser (voir Pièges)

### Events Spring publiés / écoutés

Aucun nouvel event Spring introduit par cette story — le règlement est écrit en ligne dans les flux de capture/webhook existants.

### Pièges et points d'attention

- **`PaymentEntity` a maintenant `@DynamicUpdate`.** Raison : `markCapturedIfEscrow` (bulk JPQL, ne touche jamais la session Hibernate) laisse l'entité chargée en mémoire "périmée" sur `captured_at` et les colonnes settlement. Un `save()` ultérieur sur cette même entité (ex. `NegotiationCaptureListener` persistant `stripeChargeId` juste après) émettait sans `@DynamicUpdate` un `UPDATE` full-column qui écrasait silencieusement le règlement qui venait d'être posé — **et défaisait au passage la garde anti-double-capture** (le `WHERE captured_at IS NULL` se retrouvait de nouveau vrai en base). `@DynamicUpdate` fait qu'Hibernate ne génère l'`UPDATE` que sur les colonnes réellement modifiées en mémoire, réglant les deux problèmes d'un coup. **Ne jamais retirer cette annotation sans revérifier ce mécanisme.**
- **`AdminPaymentController.refund()` n'a pas besoin du même comblement** que `forceRelease()` : sa branche `requires_capture` fait `pi.cancel()` (annulation, rien n'est capturé/réglé), pas une capture.
- **Zone CFA composée dans `CountryCurrencies`** : XOF (UEMOA — SN, CI, ML, BF, BJ, TG, NE, GW) et XAF (CEMAC — CM, GA, TD, CG, CF, GQ) partagent la même parité (655,957) mais ne sont **jamais interchangeables** — toujours résoudre la devise depuis le pays du wallet, jamais depuis le profil de l'expéditeur.
- **Point ouvert, non corrigé dans cette story** : le comblement du force-release couvre le cas où la capture se produit dans `forceRelease()` lui-même ; il n'existe pas de garantie équivalente pour un paiement capturé entièrement en dehors de l'application (ex. capture manuelle depuis le Dashboard Stripe) — dans ce cas le comblement s'applique aussi (`settlementCurrency == null` reste vrai), donc le cas est en fait couvert, mais aucun test dédié à ce scénario précis n'existe.
- **`PriceBreakdown.commissionCents()` a été supprimée** — c'était du code mort (aucun appelant en production).

## Critères d'acceptation couverts

- [x] EUR reste la devise contractuelle unique — aucune colonne monétaire existante modifiée
- [x] Table `currencies` en base, parité modifiable sans redéploiement (échéance eco 2027 anticipée)
- [x] Tous les sites de conversion existants migrés vers `MinorUnits` (sens sortant et entrant), aucun `×100`/`÷100` résiduel hors exclusions documentées
- [x] Règlement écrit atomiquement à la capture Stripe et au webhook mobile money, y compris sur le chemin admin force-release
- [x] Les 3 bugs mobile money originaux corrigés : montant net (pas valeur déclarée), devise du wallet (pas XOF en dur), montant gelé à l'initiation
- [x] Créances de commission visibles côté admin, montant calculé sur le taux figé (pas recalculé en direct)
- [x] Endpoint public `GET /config/currencies` sans modification de `SecurityConfig`

## Tests

- `./mvnw test` → 0 rouge (hors un flake JVM ponctuel sans lien avec le code, déjà rencontré et écarté plusieurs fois pendant l'implémentation)
- Chacune des 13 tasks a été revue individuellement (conformité spec + qualité) avant d'enchaîner sur la suivante ; 8 fixes ont été appliqués et re-vérifiés au fil de l'eau
- Revue finale whole-branch (opus) : arithmétique XOF/XAF reconfirmée indépendamment end-to-end, migration de conversion confirmée complète par grep frais sur l'arbre final, 2 findings Important trouvés et corrigés après la revue finale (voir Décisions techniques)
- `PaymentRepositoryMarkCapturedIfEscrowTest` — test `@DataJpaTest` (Postgres réel) prouvant que l'écriture settlement est réellement atomique (SQL généré à une seule commande) et que la garde anti-double-capture protège aussi les colonnes settlement (CAS race testée)
- `SettlementColumnsTest` — profil `e2e` (Postgres réel via Testcontainers), seul moyen de vérifier qu'une contrainte SQL réelle tient (le profil `test`/H2 désactive Flyway, contrainte historique du repo)

## Décisions techniques

| Décision | Choix | Alternatives écartées | Raison |
|---|---|---|---|
| Arrondi transactionnel vs indicatif | Deux fonctions distinctes (`toMinor` vs `MoneyRounding.roundTransactionalMinor`) | Un seul arrondi partout | Le débit réel doit tomber sur un multiple physique (5 F CFA) ; l'affichage n'a pas cette contrainte et perdrait en précision inutilement |
| Écriture du règlement | Étendre le `UPDATE` JPQL de `markCapturedIfEscrow` lui-même | "Set les champs puis save()" sur l'entité déjà chargée | La seconde option a une fenêtre de staleness JPA — prouvé être un vrai risque (voir Pièges), corrigé structurellement en rendant l'écriture atomique |
| `@DynamicUpdate` sur `PaymentEntity` | Ajouté globalement | Un `entityManager.refresh()` ciblé, ou une requête `@Modifying` dédiée pour `stripeChargeId` | Fixe la classe de bug à la racine pour tout futur code touchant cette entité, pas seulement le site actuel — et referme un bug préexistant du même mécanisme sans changement de comportement ailleurs |
| Bug préexistant `NegotiationCaptureListener` (contournement garde anti-double-capture) | Non traité comme scope initial de cette story (Task 11), puis refermé comme effet de bord confirmé du fix `@DynamicUpdate` (revue finale) | Fix dédié séparé | Même cause racine que le problème de settlement — un seul correctif règle les deux, économise un cycle de revue supplémentaire |
| Défaut DB `'XOF'` sur `mobile_money_payments.currency` | Retiré via migration additive V180 | Laisser (l'application le fixe toujours explicitement aujourd'hui) | Piège latent pour un futur chemin d'insertion qui oublierait de le faire — coût de suppression nul, risque futur réel |
| Créance de commission — taux affiché | Taux figé sur le bid au moment de l'échec (`bid.getCommissionRate()`) | Recalcul en direct via `computeBidCommission` | Le taux global a déjà changé une fois dans l'historique du projet (12%→5%) et les overrides admin par utilisateur peuvent changer à tout moment — un recalcul en direct afficherait un montant différent de ce qui a réellement été tenté |
