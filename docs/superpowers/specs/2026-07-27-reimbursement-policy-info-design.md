# Retrait valeur déclarée + politique de remboursement informative

**Date:** 2026-07-27
**Repos concernés:** yadony-back (branche `feature/reimbursement-policy-info`), yadony_app (branche `feature/reimbursement-policy-info`)

## Contexte

Le champ "valeur déclarée" (€) sur le formulaire d'envoi de colis n'est jamais utilisé dans aucune logique métier (pas de cap de remboursement, pas d'escrow, pas de calcul de compensation). Il sert uniquement une validation (`@DecimalMax(500)`) et un affichage lecture seule. On le retire entièrement et on le remplace par un texte informatif expliquant la politique de remboursement yadony en cas de perte de colis : montant plafond configurable (défaut 50€), jamais automatique, soumis à conditions.

## 1. Backend — variable de configuration

Suit le pattern déjà en place pour `yadony.commission.rate` (`CommissionProperties`, `ConfigController`).

- `application.yml` : remplace la clé morte `yadony.max-declared-value-eur` par :
  ```yaml
  yadony:
    reimbursement:
      max-amount-eur: ${YADONY_REIMBURSEMENT_MAX_AMOUNT_EUR:50}
  ```
- Nouvelle classe `ReimbursementProperties` (`@ConfigurationProperties(prefix = "yadony.reimbursement")`, record `maxAmountEur: BigDecimal`)
- Nouvel endpoint public dans `ConfigController` existant :
  - `GET /config/reimbursement-cap` → `ReimbursementCapResponse(BigDecimal maxAmountEur)`
  - Public (pas d'auth requise, même famille que `/config/commission-rate`, `/config/urgency-threshold`)

## 2. Backend — retrait complet de `declaredValueEur`

- Nouvelle migration Flyway `V(n+1)__drop_declared_value_eur.sql` :
  ```sql
  ALTER TABLE bids DROP COLUMN declared_value_eur;
  ALTER TABLE package_requests DROP COLUMN declared_value_eur;
  ALTER TABLE disputes DROP COLUMN declared_value_eur;
  ```
- Retrait champ + accesseurs : `BidEntity`, `PackageRequestEntity`, `DisputeEntity`
- Retrait validation : `BidRequest.declaredValueEur` (`@NotNull`/`@DecimalMin`/`@DecimalMax`), `BidCheckoutRequest.declaredValueEur`, `PackageRequestCompleteDetailsRequest.declaredValueEur`
- Retrait passthrough : `BidService`, `BidCheckoutService`, `ThreadAcceptedBidListener`, `PackageRequestService`
- Retrait des DTOs admin lecture seule : `AdminBidDetailResponse`, `AdminDisputeDetailResponse`
- Nettoyage des tests qui référencent `declaredValueEur` (builders, tests de migration type `V89MigrationTest`, tests admin DTO)

**Non-scope explicite :** aucune logique d'enforcement backend sur le plafond de remboursement. Le processus `AdminGuaranteeFundRequest` (module disputes, `POST /admin/disputes/{id}/guarantee-fund`) reste inchangé — montant libre saisi par l'admin après investigation manuelle. Les conditions d'éligibilité (voir §4) sont évaluées manuellement par l'équipe yadony, pas validées par code.

## 3. Flutter — retrait champ + fetch config

- `config_datasource.dart` / `config_repository.dart` / `config_bloc.dart` : ajoute `getReimbursementCap()` (même pattern que `getCommissionRate()`)
- `yadony_pricing.dart` : ajoute cache global `yadonyReimbursementCapEur` + `kYadonyReimbursementCapDefault = 50`, initialisé au démarrage dans `main.dart` (même endroit que `setYadonyCommissionRate`)
- `create_bid_bottom_sheet.dart` : retire le bloc `_SectionLabel('VALEUR DÉCLARÉE (€)')` + `TextFormField _valueCtrl` (~L902-931) et son validator (~L450-458). Remplace par un banner informatif.
- `complete_details_screen.dart` (~L271) : même retrait, même banner.
- `colis_card.dart` (~L23), `colis_destinataire_card.dart` (~L47) : retire l'affichage lecture seule de la valeur déclarée (donnée n'existe plus).

### Contenu du banner (dans les 2 écrans)

> "En cas de perte confirmée après recherche, yadony rembourse jusqu'à {montant} € sous conditions. [Voir conditions]"

Le lien "Voir conditions" pointe vers l'entrée FAQ détaillée (§4).

## 4. FAQ — conditions d'éligibilité

Réécriture de l'entrée `faq_screen.dart` (~L103), qui mentionnait l'ancien plafond 500€/assurance.

> "yadony ne couvre pas automatiquement la perte d'un colis. En cas de perte confirmée après recherche de notre équipe, un remboursement jusqu'à {montant} € peut être accordé si toutes les conditions suivantes sont respectées :
> - Paiement effectué par carte via yadony (jamais en espèces)
> - Aucun échange ou paiement effectué en dehors de la plateforme avec le voyageur
> - Colis scanné via QR code yadony au dépôt et à la remise
> - Litige signalé dans l'application dans les 15 jours suivant la date de livraison prévue
> - Contenu du colis conforme aux objets autorisés par yadony
>
> Le remboursement n'est jamais automatique et reste soumis à validation de l'équipe yadony après investigation."

Le montant `{montant}` est injecté dynamiquement depuis la config (`yadonyReimbursementCapEur`), jamais codé en dur dans le texte Dart.

## 5. Tests

**Backend (`./mvnw test` + `jacoco:report`, couverture ≥ 90%) :**
- Test `ConfigController` : `GET /config/reimbursement-cap` retourne la valeur configurée
- Test `ReimbursementProperties` binding
- Migration test : colonnes `declared_value_eur` absentes après `V(n+1)`
- Nettoyage de tous les tests référençant `declaredValueEur` sur `BidRequest`/`PackageRequestCompleteDetailsRequest`/entités admin

**Flutter (`flutter test --coverage`, couverture ≥ 90%) :**
- `config_bloc_test.dart` : chargement du plafond de remboursement, fallback sur `kYadonyReimbursementCapDefault` si config indisponible
- Widget tests `create_bid_bottom_sheet` et `complete_details_screen` : absence du champ valeur déclarée, présence du banner avec le bon montant
- Widget test FAQ : entrée réécrite affichée correctement
- Nettoyage des tests widget qui remplissaient `_valueCtrl` ou assertaient le validator 500€

## Hors scope

- Pas d'enforcement backend du plafond sur le paiement du guarantee-fund admin (décision explicite, voir §2)
- Pas de modification du processus de dispute existant (`disputes/` package)
- FAQ : seule l'entrée sur la valeur déclarée/assurance est réécrite, pas d'audit du reste du contenu FAQ
