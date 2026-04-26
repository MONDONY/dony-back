# Story 7.8 — Numéro de suivi court DON-XXXXXX (Backend)

**Date:** 2026-04-26
**Status:** ✅ Complète

## Résumé
Chaque bid accepté reçoit un numéro de suivi unique au format `DON-XXXXXX` (6 caractères sans ambiguïté). Ce numéro permet aux expéditeurs de partager le suivi avec leur destinataire en Afrique sans que celui-ci ait besoin du QR code ni de l'application.

## Fichiers créés
- `matching/BidService.java` — méthode `generateTrackingNumber()` + assignation à l'acceptation
- `tracking/dto/TrackingSearchResponse.java` — DTO de la réponse de recherche publique
- `db/migration/V21__bids_add_tracking_number.sql` — colonne + index unique partiel

## Fichiers modifiés
- `matching/BidEntity.java` — champ `trackingNumber` + getter/setter
- `matching/dto/BidResponse.java` — ajout du champ `trackingNumber` en dernier paramètre
- `matching/BidRepository.java` — méthode `findByTrackingNumber(String)`
- `tracking/TrackingService.java` — méthode `searchByTrackingNumber()` + injection `AnnouncementRepository`
- `tracking/TrackingController.java` — endpoint `GET /tracking/search?number=`
- `config/SecurityConfig.java` — `/tracking/search` ajouté au `permitAll`

## Comment ça fonctionne

### Vue d'ensemble du flux

**Génération (à l'acceptation d'un bid) :**
1. Traveler appelle `POST /bids/{id}/accept`
2. `BidService.acceptBid()` génère le `trackingNumber` via `generateTrackingNumber()` (boucle de retry sur collision)
3. Le numéro est persisté dans `bids.tracking_number`
4. La réponse `BidResponse` inclut désormais le `trackingNumber`

**Recherche publique :**
1. N'importe qui appelle `GET /tracking/search?number=DON-XXXXXX` (sans token)
2. `TrackingService.searchByTrackingNumber()` normalise en uppercase, trouve le bid, récupère l'annonce et le paiement
3. Retourne `TrackingSearchResponse` avec corridor, step courant et libellé humain

### Points d'entrée API
- `GET /tracking/search?number={DON-XXXXXX}` — public (pas de token requis), accessible par le destinataire depuis n'importe quel navigateur

### Format du numéro
```
DON- + 6 caractères parmi ABCDEFGHJKMNPQRSTUVWXYZ23456789
```
Alphabet sans ambiguïtés : pas de `O/0`, `I/1`, `L`, `S/5`. 32 chars → 32⁶ = ~1 milliard de combinaisons.

### Logique de mapping statut → étape
| Statut bid | Statut paiement | voyageurConfirmed | Étape retournée |
|---|---|---|---|
| PENDING | — | — | `PENDING` |
| ACCEPTED | PENDING/absent | — | `ACCEPTED` |
| ACCEPTED | ESCROW | false | `PAYMENT_SECURED` |
| ACCEPTED | ESCROW | true | `IN_TRANSIT` |
| ACCEPTED | RELEASED | — | `DELIVERED` |
| REJECTED | — | — | `REJECTED` |
| CANCELLED | — | — | `CANCELLED` |

### Migration Flyway
```sql
-- V21
ALTER TABLE bids ADD COLUMN tracking_number VARCHAR(12);
CREATE UNIQUE INDEX uq_bids_tracking_number ON bids (tracking_number)
    WHERE tracking_number IS NOT NULL;
```
Index partiel (`WHERE IS NOT NULL`) pour ne pas bloquer les anciens bids sans numéro.

### Pièges et points d'attention
- **Retry sur collision** : `generateTrackingNumber()` vérifie `existsByTrackingNumber()` avant d'assigner (probabilité de collision négligeable mais gérée).
- **Acceptation seulement** : le numéro n'est généré qu'à l'acceptation, pas à la création du bid. Les bids PENDING n'ont pas de numéro.
- **`@Where(deleted_at IS NULL)`** sur `BidEntity` : un bid soft-deleted n'est pas trouvable par son numéro de suivi — comportement voulu.
- **Cross-package** : `TrackingService` injecte directement `AnnouncementRepository` (matching package) pour un read-only. Accepté comme exception au pattern events car pas de mutation impliquée.

## Critères d'acceptation couverts
- [x] Chaque bid accepté reçoit un numéro `DON-XXXXXX` unique
- [x] `GET /tracking/search?number=` retourne corridor + étape sans auth
- [x] 404 si numéro inconnu avec message explicite
- [x] Numéro inclus dans `BidResponse` (visible par sender et traveler)
- [x] Endpoint public ajouté dans `SecurityConfig`

## Décisions techniques
- **Alphabet 32 chars sans ambiguïté** plutôt qu'un UUID tronqué : plus lisible oralement (le destinataire peut épeler le numéro par téléphone).
- **Index unique partiel** plutôt que `NOT NULL UNIQUE` : préserve les anciens bids migrés sans numéro sans lever de violation de contrainte.
- **`AnnouncementRepository` injecté directement dans `TrackingService`** plutôt que via un event : les events Spring sont réservés aux mutations; une lecture simple de l'annonce pour le corridor ne justifie pas un event asynchrone.
