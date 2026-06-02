# Spec — Taux de commission : override par utilisateur + codes promo

**Statut :** 📋 Spec (non implémentée) · **Date :** 2026-06-02
**Périmètre :** backend `com.dony.api.*` + app Flutter · **Pré-requis :** refactor « source unique `dony.commission.rate` » (déjà livré, branche `feature/price-coherence`).

---

## 1. Contexte & état actuel

Depuis le refactor « source unique », le taux de commission Dony vient d'**un seul** endroit : la propriété `dony.commission.rate` (défaut `0.12`, surchargeable par `DONY_COMMISSION_RATE`). Elle pilote :
- le **montant facturé** : `PaymentService` → `amount = totalNet × (1 + rate)` ;
- les **prix affichés** : `PriceGridService.displayPrice(net)` → `unitPriceDisplay` (MIXED) **et** `pricePerKgDisplay` (KG), consommés par toutes les surfaces app ;
- les **stats PRO** : `ProAnalyticsService`.

Modèle métier : `pricePerKg`/`unitPriceNet` = **NET voyageur** (ce qu'il touche). L'expéditeur paie `net × (1 + rate)` ; Dony garde `net × rate` ; le voyageur reçoit le net.

Existant connexe : **parrainage à crédits €** (`ReferralService`, `UserCreditEntity`) — un grand livre de crédits en centimes, **non appliqué au paiement** aujourd'hui. Distinct du taux de commission.

---

## 2. Objectif

1. **Taux spécifique par utilisateur** (override) — un voyageur et/ou un expéditeur peut avoir un taux différent du global.
2. **Code promo** qui **réduit le taux** de commission pour une transaction.

### Décisions métier verrouillées
| Décision | Choix |
|---|---|
| L'override par utilisateur s'applique à… | **Voyageur ET expéditeur** (les deux, avec règle de priorité) |
| Mécanisme du code promo | **Réduction du taux de commission** (pas un crédit €) |
| Bénéficiaire d'une commission réduite | **L'expéditeur paie moins** (net voyageur inchangé, Dony réduit sa marge) |

---

## 3. Règle de résolution du taux effectif

Un **`CommissionRateResolver`** devient le SEUL point qui décide du taux. Tous les consommateurs (`PaymentService`, `PriceGridService`, `ProAnalyticsService`, endpoint devis) l'appellent avec le contexte dont ils disposent.

```
tauxEffectif(travelerId, senderId?, promoCode?) =
  1. si promoCode valide pour ce contexte   → rate(promoCode)        // le promo ÉCRASE (priorité 1)
  2. sinon                                   → min( override(traveler), override(sender), global )
```

- **Le promo écrase** (et ne se cumule pas avec le `min`) pour maîtriser son coût et sa durée.
- En l'absence de promo, on prend **le plus favorable** (`min`) — cohérent avec « l'expéditeur paie le moins ». Réglable si une priorité stricte est préférée.
- `total = net × (1 + tauxEffectif)` ; **le net voyageur ne change jamais**.

### Quand le taux est-il connu ? (impact affichage)
| Contexte | Connu… | Affichage annonces (`*Display`) |
|---|---|---|
| Override **voyageur** | dès la navigation (annonce ⇒ voyageur connu) | ✅ calculé avec `min(override(traveler), global)` |
| Override **expéditeur** / **promo** | seulement au checkout | ❌ n'impacte que le total au paiement |

⇒ Le prix au checkout peut être **inférieur** au prix catalogue affiché (comme un coupon e-commerce). **L'UI doit afficher** « tarif préférentiel » / « code appliqué : −X % » pour expliquer la baisse.

---

## 4. Règle d'or : figer le taux sur le bid

Comme `commission_charged_via`, on **snapshot le taux effectif sur le bid** au moment de l'acceptation/du paiement :
- `bids.commission_rate` (DECIMAL) — taux réellement appliqué ;
- `bids.promo_code_id` (UUID, nullable) — promo utilisé le cas échéant.

**Raison :** le taux global, l'override utilisateur ou le promo peuvent changer après. Un bid accepté — et ses remboursements, payouts, audit, analytics — doit garder le taux avec lequel il a été créé. **Ne jamais recalculer depuis le taux courant a posteriori.** Les remboursements (cf. `CommissionRefundListener`, `CardCommissionTripCancelRefundListener`, `WalletCancellationListener`) doivent lire `bids.commission_rate`, pas le global.

---

## 5. Modèle de données

```sql
-- Phase 1 : override par utilisateur
ALTER TABLE users ADD COLUMN commission_rate_override DECIMAL(4,3);  -- null = taux global

-- Phase 2 : codes promo
CREATE TABLE promo_codes (
    id              UUID PRIMARY KEY,
    code            VARCHAR(40) NOT NULL UNIQUE,           -- normalisé upper-case
    rate            DECIMAL(4,3) NOT NULL,                 -- taux de commission appliqué (ex. 0.000, 0.060)
    target          VARCHAR(10) NOT NULL,                  -- SENDER | TRAVELER | ANY (qui peut l'utiliser)
    valid_from      TIMESTAMP,
    valid_to        TIMESTAMP,
    max_redemptions INTEGER,                               -- null = illimité
    per_user_limit  INTEGER NOT NULL DEFAULT 1,
    redeemed_count  INTEGER NOT NULL DEFAULT 0,
    status          VARCHAR(10) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | DISABLED
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    deleted_at      TIMESTAMP
);

CREATE TABLE promo_redemptions (
    id           UUID PRIMARY KEY,
    promo_code_id UUID NOT NULL REFERENCES promo_codes(id),
    user_id      UUID NOT NULL,        -- qui a utilisé
    bid_id       UUID NOT NULL,        -- sur quel bid
    applied_rate DECIMAL(4,3) NOT NULL,
    redeemed_at  TIMESTAMP NOT NULL,
    UNIQUE (promo_code_id, bid_id)
);

-- Snapshot sur le bid
ALTER TABLE bids ADD COLUMN commission_rate DECIMAL(4,3);   -- backfill avec le global pour l'existant
ALTER TABLE bids ADD COLUMN promo_code_id UUID REFERENCES promo_codes(id);
```

> Migrations Flyway `V(n+1)` (ne jamais modifier l'existant). Backfill `bids.commission_rate` = valeur de `dony.commission.rate` au moment de la migration pour les bids déjà créés.

---

## 6. Backend — composants

- **`CommissionRateResolver`** (`payments/` ou `common/`) :
  `BigDecimal resolve(UUID travelerId, UUID senderId /*nullable*/, String promoCode /*nullable*/)`.
  Lit `users.commission_rate_override`, valide le promo, applique la règle §3. Source unique.
- **`PriceGridService.displayPrice(BigDecimal net, UUID travelerId)`** : signature enrichie ⇒ `net × (1 + resolver.resolve(travelerId, null, null))`. Impacte `unitPriceDisplay` + `pricePerKgDisplay`.
- **`PaymentService`** : `rate = resolver.resolve(traveler, sender, promoCode)` ; `amount = totalNet × (1 + rate)` ; persiste `bids.commission_rate` + `promo_code_id` + crée la `promo_redemption` (idempotent, dans la même tx que l'acceptation).
- **`ProAnalyticsService`** : utilise `bids.commission_rate` (snapshot) plutôt que le global, pour des stats exactes.
- **Validation promo** (dans le resolver ou un `PromoService`) : existe & `ACTIVE`, fenêtre `valid_from/to`, `redeemed_count < max_redemptions`, `per_user_limit` non dépassé pour cet utilisateur, `target` compatible. Erreurs RFC 7807 (`promo-not-found`, `promo-expired`, `promo-limit-reached`, `promo-not-eligible`).
- **Admin** : endpoints `PUT /admin/users/{id}/commission-rate` (override) et CRUD `promo_codes` sous `@PreAuthorize("hasRole('ADMIN')")` + entrées `audit_log`.

### Endpoint devis (clé pour le front)
`POST /bids/quote` (auth expéditeur) → body `{announcementId, weightKg|gridItems, promoCode?}` → `{ net, rate, commission, total, promoApplied: bool, promoLabel? }`.
Permet à l'app d'afficher le total exact **sans** le calculer localement (impossible avec un promo).

---

## 7. Front (app Flutter)

- **Override voyageur** : rien de neuf — les cartes/écrans lisent déjà `pricePerKgDisplay`/`unitPriceDisplay` (calculés backend avec le taux du voyageur). Le `donyCommissionRate` global reste un repli.
- **Code promo** :
  - Champ « code promo » dans `CreateBidBottomSheet` / `create_bid_screen`.
  - **Arrêter le calcul local** du total quand un code/override entre en jeu → appeler `POST /bids/quote` et afficher `total` + `commission` renvoyés. (Aujourd'hui : `netToSenderPrice` local, qui ne connaît pas la remise.)
  - Message « code appliqué : −X % » + état d'erreur (code invalide/expiré) via `ErrorCatalog` (ajouter `promo-*`).
- `payment_screen` : afficher le `total`/`commission` du devis (déjà dynamique via ConfigBloc ; basculer sur le devis quand promo).

---

## 8. Découpage & risques

**Phase 1 — Override par utilisateur** : colonne `users.commission_rate_override` + `CommissionRateResolver` + snapshot `bids.commission_rate` + branchement `PaymentService`/`PriceGridService`/`ProAnalytics` + admin override + tests. Pas de changement front visible.

**Phase 2 — Codes promo** : tables `promo_codes`/`promo_redemptions` + validation + `promo_code_id` sur le bid + endpoint devis + admin CRUD + UI saisie code + reparse erreurs. 

**Risques / points d'attention :**
- **Remboursements** : router/recalculer via `bids.commission_rate` (snapshot), jamais le global. Revue des 3 listeners de remboursement.
- **Cohérence affichage↔checkout** : assumée (coupon), mais à expliciter en UI.
- **Idempotence promo** : `UNIQUE(promo_code_id, bid_id)` + incrément `redeemed_count` sous verrou ; gérer le ré-essai de paiement sans double-décompte.
- **Cumul** : règle `min`/écrasement promo à valider à l'usage (anti-abus : un override voyageur généreux + promo ne doivent pas s'empiler — d'où « le promo écrase »).
- **Mobile money / cash** (commission prélevée au voyageur côté `CashCommissionService`) : le resolver doit aussi y être branché pour rester cohérent (le `bids.commission_rate` sert de référence).

---

## 9. Journal des décisions
- 2026-06-02 : override **voyageur + expéditeur** ; promo = **réduction de taux** ; bénéficiaire = **expéditeur paie moins**. Règle : `promo écrase, sinon min(overrides, global)`. Snapshot obligatoire sur le bid.
