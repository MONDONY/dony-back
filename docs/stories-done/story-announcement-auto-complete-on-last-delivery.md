# Story Traveler Trips — Auto-COMPLETED de l'annonce sur livraison du dernier bid (Backend)

**Date:** 2026-05-04
**Status:** ✅ Complète

## Résumé
Ajoute un listener qui bascule automatiquement une annonce à `COMPLETED` quand son dernier bid `ACCEPTED` est livré (scan QR de fin), même si la date de départ n'est pas atteinte. Côté Flutter "Mes trajets", l'annonce passe alors directement de l'onglet "À venir" vers "Historique".

## Fichiers créés
- `src/main/java/com/dony/api/matching/AnnouncementCompletionListener.java` — listener `@TransactionalEventListener(AFTER_COMMIT)` sur `DeliveryConfirmedEvent`.
- `src/test/java/com/dony/api/matching/AnnouncementCompletionListenerTest.java` — 6 tests unitaires.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble
1. Le voyageur scanne le QR code de fin sur un colis.
2. `TrackingService.confirmDelivery` → `bid.status = COMPLETED` + publie `DeliveryConfirmedEvent`.
3. Plusieurs listeners écoutent (paiements, stats voyageur, badges, ratings…). On en ajoute un **dans `matching/`** : `AnnouncementCompletionListener`.
4. Le listener charge le bid + l'annonce. Si l'annonce n'est ni déjà `COMPLETED` ni `CANCELLED`, il vérifie via `bidRepository.existsByAnnouncementIdAndStatus(annId, ACCEPTED)`.
5. S'il **n'existe plus aucun bid `ACCEPTED`** → l'annonce passe à `COMPLETED`, audit log `ANNOUNCEMENT_COMPLETED`.

### Règle métier précise
Un trajet est "terminé" dès lors que **plus aucun colis accepté n'est en cours de livraison**. Plus formellement : aucun bid sur l'annonce n'est en statut `ACCEPTED`. Les statuts `PENDING` et `AWAITING_PAYMENT` (pré-acceptation) ne sont **pas** pris en compte — ce sont des engagements potentiels que le voyageur n'a pas encore validés. Les statuts terminaux `COMPLETED`, `CANCELLED`, `NO_SHOW`, `PARCEL_REFUSED` ne bloquent pas la transition (le bid n'est plus en flight).

### Cas couverts
| Scénario | Résultat |
|---|---|
| 1 seul bid `ACCEPTED` → livré | Annonce → `COMPLETED` immédiatement |
| 3 bids `ACCEPTED` ; le 1er livré → reste 2 `ACCEPTED` | Annonce inchangée |
| 3 bids `ACCEPTED` ; les 3 livrés successivement | À chaque livraison le listener recalcule. À la 3ᵉ → `existsByAcceptedStatus = false` → annonce `COMPLETED` |
| 1 bid `ACCEPTED` livré + 1 bid `NO_SHOW` | Pas d'`ACCEPTED` restant → annonce `COMPLETED` |
| 1 bid livré sur annonce déjà `CANCELLED` | Aucun changement (idempotence) |

### Idempotence et garanties
- `@TransactionalEventListener(phase = AFTER_COMMIT)` : si la transaction tracking rollback, la transition n'a pas lieu.
- `@Transactional(propagation = REQUIRES_NEW)` : le listener tourne dans sa propre transaction, isolée du reste.
- Si l'annonce est déjà `COMPLETED` ou `CANCELLED`, on ne fait rien (no-op silencieux).
- Si le bid ou l'annonce sont introuvables → log `WARN`, pas d'exception.

### Cas non couverts par ce listener (volontairement)
- **Trajet sans aucun bid accepté + date passée** : l'annonce reste `ACTIVE`. Le front-end Flutter (`announcement_list_screen._isHistory`) bascule la card en "Historique" via le fallback `dateDeparture < today && status != CANCELLED`.
- **Annonce avec bids tous `NO_SHOW` ou `PARCEL_REFUSED`** : aucun `DeliveryConfirmedEvent` n'est publié (pas de COMPLETED), donc ce listener ne se déclenche jamais. Idem, le fallback front gère.

## Critères d'acceptation
- [x] Quand le dernier bid est livré, l'annonce devient `COMPLETED` même avant la date de départ.
- [x] L'audit log trace la transition avec le statut précédent + l'id du bid déclencheur.
- [x] Idempotent : pas de double transition.

## Tests
- `./mvnw test` → 431 tests passent, 0 failures, 0 errors.
- Nouveaux tests :
  - `lastBidCompleted_setsAnnouncementCompleted`
  - `otherAcceptedBidsRemain_keepsStatus`
  - `alreadyCompleted_isIdempotent`
  - `cancelled_doesNothing`
  - `unknownBid_skips`
  - `unknownAnnouncement_skips`

## Décisions techniques
- **Listener dans `matching/` plutôt que dans `tracking/`** : c'est l'annonce (entité métier de `matching`) qui change d'état. `tracking` reste émetteur, conformément à la règle architecturale "communication inter-package via Spring Events".
- **Pas de migration de fond pour rattraper l'historique** : les annonces existantes dont tous les bids sont déjà `COMPLETED` resteront en `ACTIVE` jusqu'à ce que la date passe, et le fallback front les rangera en "Historique". Pas critique, et ça évite une migration risquée. Si besoin, un script ponctuel pourra être ajouté plus tard.
- **PENDING ignoré** : un bid `PENDING` est une demande qu'un sender peut encore retirer ou que le voyageur peut accepter/rejeter. Ne pas attendre leur résolution pour considérer le trajet "fini" — le voyageur a déjà livré tous les colis qu'il a acceptés. Si le voyageur veut rejeter les `PENDING` restants, c'est une action manuelle (qui pourrait devenir automatique dans une story future si le besoin émerge).
