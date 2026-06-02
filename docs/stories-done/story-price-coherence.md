# Story — Cohérence des prix : prix expéditeur (net + 12 %) exposé côté API (Backend)

**Date:** 2026-06-02
**Status:** ✅ Complète

## Résumé
Pour le mode KG, le backend n'exposait que `pricePerKg` (= NET du voyageur) ; le prix
« affiché expéditeur » (net + commission) n'existait que pour le mode MIXED via
`unitPriceDisplay`. Résultat : les surfaces app montraient tantôt le net, tantôt le TTC.
On expose désormais **`pricePerKgDisplay` = `pricePerKg × 1,12`** sur les 3 DTO d'annonce,
symétrique de `unitPriceDisplay`, pour une source de vérité unique côté serveur.

## Modèle métier (rappel, vérifié)
- `pricePerKg` (KG) et `unitPriceNet` (MIXED) = **NET** = ce que le voyageur touche.
- L'expéditeur paie **net × 1,12** ; Dony garde les 12 %. Cf. `PaymentService` :
  `amount = totalNet × 1.12`, `commission = totalNet × rate`, transfert voyageur =
  `amount − commission = totalNet`. Le voyageur reçoit donc l'intégralité du net.

## Fichiers modifiés
- `matching/dto/AnnouncementResponse.java`, `AnnouncementDetailResponse.java`,
  `AnnouncementSearchResponse.java` — ajout du composant `BigDecimal pricePerKgDisplay`
  (juste après `pricePerKg`).
- `matching/AnnouncementService.java` — helper privé `pricePerKgDisplay(BigDecimal net)`
  (null-safe) délégant à `PriceGridService.displayPrice` (**même multiplicateur 1,12 que le
  MIXED** → une seule source de vérité). Câblé dans les 4 mappers (`toSearchResponse`,
  les 2 `AnnouncementDetailResponse`, `AnnouncementResponse`).
- `src/test/.../AnnouncementControllerIntegrationTest.java` — test
  `createAnnouncement_exposesPricePerKgDisplay_netPlus12Percent` (pricePerKg 5 → display 5,60).

## Comment ça fonctionne
- À chaque mapping d'annonce, `pricePerKgDisplay(entity.getPricePerKg())` calcule le prix
  TTC ; `null` si pas de prix au kilo (MIXED pur, où la grille porte déjà ses
  `unitPriceDisplay`). Aucune migration DB : champ purement dérivé.
- Le front (cf. story app) lit ce champ s'il est présent et retombe sinon sur un calcul
  `net × (1 + commissionRate)`.

## Points d'attention
- `PriceGridService.displayPrice` est package-private `static` dans `com.dony.api.matching`
  → appelable depuis `AnnouncementService` (même package). C'est volontairement la **seule**
  implémentation du multiplicateur côté annonces/grille.
- Le multiplicateur grille est en dur `1.12` (constante `COMMISSION_MULTIPLIER`), distinct du
  `dony.commission.rate` configurable utilisé par `PaymentService`. Si un jour le taux doit
  être unique/configurable partout, c'est le point à factoriser.
- Ajouter un composant à un `record` ne casse que les appels constructeur explicites
  (les 4 mappers, tous mis à jour) ; la désérialisation JSON et les accesseurs existants
  restent compatibles.

## Critères d'acceptation couverts
- [x] `pricePerKgDisplay = pricePerKg × 1,12` exposé sur les réponses annonce (KG).
- [x] Symétrie avec `unitPriceDisplay` du MIXED ; même multiplicateur (source unique).
- [x] Aucun changement de schéma / migration (champ dérivé).

## Tests
- `./mvnw compile` OK. `AnnouncementControllerIntegrationTest` → **9/9** (dont le nouveau).
- Aucun autre site de construction des DTO (vérifié : 4 mappers, 0 dans les tests).
