# Story 8.2 — Notifications push transactionnelles (Backend)

**Date:** 2026-04-26
**Status:** ✅ Complète

## Résumé
Implémentation des notifications push automatiques pour tous les événements métier clés du cycle de vie d'un colis, via `NotificationDispatcher` qui écoute les Spring Events et envoie les FCM via `FcmService`.

## Fichiers créés

- `src/main/java/com/dony/api/matching/events/BidCreatedEvent.java` — event publié par `BidService` après création d'une offre, enrichi avec le nom de l'expéditeur et le corridor
- `src/main/java/com/dony/api/payments/events/PaymentReleasedEvent.java` — event publié par `DeliveryEventListener` après capture Stripe réussie
- `src/main/java/com/dony/api/disputes/events/DisputeOpenedEvent.java` — event déclaré pour Epic 10, structure prête pour quand `DisputeService` le publiera
- `src/test/java/com/dony/api/notifications/NotificationDispatcherTest.java` — 11 tests unitaires couvrant tous les event listeners

## Fichiers modifiés

- `src/main/java/com/dony/api/notifications/NotificationDispatcher.java` — ajout de tous les `@EventListener` pour les 8 types d'événements ; ajout injection `UserRepository` pour lookup du prénom du voyageur sur `BidAcceptedEvent`
- `src/main/java/com/dony/api/matching/BidService.java` — publication de `BidCreatedEvent` après sauvegarde du bid, avec prénom de l'expéditeur et corridor formaté
- `src/main/java/com/dony/api/payments/DeliveryEventListener.java` — ajout `ApplicationEventPublisher` et publication de `PaymentReleasedEvent` après capture Stripe réussie
- `src/main/java/com/dony/api/tracking/events/DeliveryConfirmedEvent.java` — enrichissement avec `travelerId` (était absent, nécessaire pour `PaymentReleasedEvent`)
- `src/main/java/com/dony/api/tracking/TrackingService.java` — mise à jour de la publication de `DeliveryConfirmedEvent` pour passer `senderId` et `travelerId`

## Comment ça fonctionne

### Vue d'ensemble du flux

Chaque événement métier suit le même chemin :

1. Un service métier publie un Spring Event via `ApplicationEventPublisher.publishEvent()`
2. `NotificationDispatcher` reçoit l'event via `@EventListener @Async`
3. Le dispatcher appelle `notifyUser(userId, title, body, data)` qui délègue à `FcmService.sendToUser()`
4. `FcmService` récupère le token FCM de l'utilisateur en base, construit le message Firebase et l'envoie
5. Si le token est UNREGISTERED, il est effacé de la base automatiquement

### Événements couverts et destinataires

| Event | Publié par | Destinataire | Titre |
|---|---|---|---|
| `BidCreatedEvent` | `BidService` | Voyageur | "Nouvelle demande d'envoi" |
| `BidAcceptedEvent` | `BidService` | Expéditeur | "Demande acceptée !" |
| `BidRejectedEvent` | `BidService` | Expéditeur | "Demande refusée" |
| `HandoverDefinedEvent` | `BidService` | Expéditeur | "Point de remise défini" |
| `TripCancelledEvent` | `CancellationService` | Tous les expéditeurs concernés | "Trajet annulé" |
| `DeliveryConfirmedEvent` | `TrackingService` | Expéditeur | "Livraison confirmée" |
| `PaymentReleasedEvent` | `DeliveryEventListener` | Voyageur | "Paiement reçu !" |
| `DisputeOpenedEvent` | `DisputeService` (Epic 10) | Expéditeur + Voyageur | "Litige ouvert" |

### Données (payload) dans chaque notification

Chaque notification inclut un `Map<String, String> data` avec au minimum `"type"` et `"bidId"`, permettant au client Flutter de naviguer vers le bon écran à la réception.

### Enrichissement des events

`BidCreatedEvent` porte `senderFirstName`, `weightKg` et `corridor` directement dans l'event, évitant tout accès à la base de données dans `NotificationDispatcher` pour ce cas. Seul `BidAcceptedEvent` nécessite un lookup `userRepository.findById(travelerId)` pour afficher le prénom du voyageur dans la notification de l'expéditeur.

### Formatage du montant (localisation)

`onPaymentReleased` utilise `String.format(Locale.FRENCH, "%.2f €", amount)` pour garantir le séparateur décimal virgule (ex: "45,00 €") quelle que soit la locale de la JVM en production ou en test.

## Critères d'acceptation couverts

- [x] BidCreatedEvent → voyageur reçoit une notification avec le nom de l'expéditeur et le corridor
- [x] BidAcceptedEvent → expéditeur reçoit une notification avec le prénom du voyageur (fallback "Le voyageur" si absent)
- [x] BidRejectedEvent → expéditeur reçoit une notification avec type BID_REJECTED dans le payload
- [x] HandoverDefinedEvent → expéditeur reçoit lieu et heure formatés
- [x] TripCancelledEvent → tous les expéditeurs de la liste `affectedSenderIds` sont notifiés ; no-op si liste vide/null
- [x] DeliveryConfirmedEvent → expéditeur reçoit confirmation de livraison
- [x] PaymentReleasedEvent → voyageur reçoit le montant formaté "45,00 €"
- [x] DisputeOpenedEvent → les deux parties (expéditeur et voyageur) reçoivent une notification

## Tests

- `./mvnw test` → 198 tests, 0 rouge, 0 erreur
- Tests ajoutés : `NotificationDispatcherTest` (11 tests, couverture complète de tous les listeners)

## Décisions techniques

**Pourquoi `UserRepository` est injecté dans `NotificationDispatcher`** : Le package `auth/` est une couche fondatrice (infrastructure) que tous les autres packages peuvent consommer. Injecter `UserRepository` dans le dispatcher pour enrichir les messages est acceptable — ce n'est pas une violation du principe "cross-package via events only" qui s'applique entre packages métier.

**Pourquoi enrichir `DeliveryConfirmedEvent` avec `travelerId`** : `DeliveryEventListener` (dans `payments/`) doit publier `PaymentReleasedEvent` avec `travelerId`, mais `PaymentEntity` ne stocke pas ce champ. Plutôt qu'ajouter une colonne ou injecter `BidRepository` dans le listener, on enrichit l'event à la source dans `TrackingService` qui dispose déjà des deux IDs.

**Pourquoi `@Async` sur tous les listeners** : Les notifications sont du "best effort" — une lenteur FCM ne doit pas bloquer le thread de traitement métier (ex: confirmation de livraison, annulation). L'async garantit que le flux principal reste rapide même si FCM est lent.
