# Moteur d'exécution des automatisations — Design

## Contexte

`dony-pro` expose une page "Automatisations" (`/automatisations`) listant 6 règles préconfigurées avec toggles, une section règles personnalisées (hors scope ici) et un historique. L'infrastructure CRUD existe déjà des deux côtés :

- **Front** (`dony-pro`) : `AutomationsDashboard.vue` / `useAutomations()` / `automationsService.ts` — appels réels à `GET/POST/PUT/DELETE /travelers/me/automation-rules` et `GET /travelers/me/automation-history`, pas de données statiques.
- **Back** (`dony-back`) : package `com.dony.api.automation` — `AutomationRuleEntity`, `AutomationRuleRepository`, `AutomationHistoryEntity/Repository`, `AutomationRuleController`, `AutomationRuleService`. CRUD complet, testé.

**Ce qui manque, et l'objet de ce chantier** : aucun moteur n'évalue ces règles ni n'agit dessus. Aucun `@Scheduled` dans `automation`, aucune référence à ce package depuis `matching`/`bids`, `AutomationHistoryRepository` n'est jamais écrit en prod.

## Périmètre

- Les **6 règles préconfigurées** sont dans le scope. Les règles personnalisées (SI→ALORS, UI "Créer une règle") restent hors scope — DSL condition/action générique à part entière.
- Ajout d'une **UI de configuration des seuils** par preset (actuellement "configurable via l'API" seulement, sans champ dans l'interface).
- **Règle 3 "Fermer automatiquement quand c'est complet" est retirée du chantier** : le comportement existe déjà de façon inconditionnelle dans `BidService.acceptBid` (lignes 500-502, fermeture à `AnnouncementStatus.FULL` dès que `availableKg` atteint 0, indépendamment de tout toggle) et sa symétrique dans `cancelBid` (lignes 609-611). Le toggle front reste affiché mais n'aura aucun effet propre — à documenter/masquer dans une itération future si besoin, pas dans ce chantier.

## Architecture

### Déclenchement par règle

| # | Règle | Déclencheur | Type |
|---|---|---|---|
| 1 | Accepter auto expéditeurs de confiance | `BidCreatedEvent` | Event |
| 2 | Refuser auto colis trop lourds | `BidCreatedEvent` | Event |
| 4 | Alerter capacité libérée | Scheduler (15 min) | Time-based |
| 5 | Notifier expéditeurs fidèles | `AnnouncementPublishedEvent` | Event |
| 6 | Alerter offre dernière minute | `BidCreatedEvent` | Event |

Point important : `BidCreatedEvent` est publié **après autorisation de paiement** (`PaymentService.promoteBidOnPaymentAuthorized`, ligne 654), pas à la création brute du bid (voir commentaire `BidService.createBid` lignes 374-376). C'est le bon point de déclenchement pour accepter/refuser : l'argent est déjà sécurisé côté expéditeur avant toute décision automatique.

### Ordonnancement règles 1 et 2

Les deux écoutent le même `BidCreatedEvent` pour un même voyageur. Ordre d'évaluation dans le listener unique `AutomationBidListener` :

1. Règle 2 (refus poids) évaluée en premier — un colis trop lourd ne peut jamais être accepté, même si l'expéditeur est noté favorablement.
2. Si pas de refus, règle 1 (accept confiance) évaluée.
3. Une seule action possible par bid (accept XOR reject XOR aucune action = laissé en attente pour décision manuelle du voyageur).

### Nouveau composant : `AutomationEngine` (package `com.dony.api.automation`)

- `AutomationBidListener` : `@EventListener` sur `BidCreatedEvent`, résout les règles actives 1/2/6 du voyageur propriétaire de l'annonce, évalue, agit.
- `AutomationAnnouncementListener` : `@EventListener` sur `AnnouncementPublishedEvent`, gère la règle 5.
- `CapacityWatchScheduler` : `@Scheduled` (15 min), gère la règle 4.
- `AutomationHistoryWriter` : petit composant partagé, écrit systématiquement une ligne dans `AutomationHistoryRepository` à chaque déclenchement (succès ou échec), avec `ruleId`, `announcementId`/`bidId`, `outcome`, `timestamp`, `detail`.

### Actions système sur les bids

`BidService.acceptBid(UUID bidId, String firebaseUid)` et `rejectBid(UUID bidId, String firebaseUid, BidRejectRequest request)` exigent un `firebaseUid` pour la vérification `requireTravelerOwnsAnnouncement`. Pour un déclenchement système, ajout de deux méthodes internes dans `BidService` :

```java
// Réservé aux appels internes (automation) — la légitimité vient du fait que
// la règle est déjà scopée au travelerId propriétaire de l'annonce, pas besoin
// de vérifier un firebaseUid inexistant côté système.
Bid acceptBidBySystem(UUID bidId, UUID triggeringRuleId)
Bid rejectBidBySystem(UUID bidId, UUID triggeringRuleId, String reason)
```

Elles réutilisent la logique métier existante (mise à jour capacité, fermeture FULL, publication `BidAcceptedEvent`/`BidRejectedEvent`) en sautant uniquement le check d'ownership HTTP-style, puisque l'appelant (`AutomationBidListener`) a déjà résolu la règle depuis le `travelerId` de l'annonce.

### Notifications

Les règles 1/2 n'ajoutent aucune notification : les listeners existants sur `BidAcceptedEvent`/`BidRejectedEvent` notifient déjà l'expéditeur (comportement identique à une action manuelle du voyageur).

Nouvelles notifications via `NotificationDispatcher.notifyUser(userId, title, body, data)` (point d'injection direct autorisé en sortie, pattern déjà utilisé par d'autres listeners du projet, ex. `PackageMatchTravelerNotifyListener`) :

- Règle 4 : notifie le **voyageur** ("Tu as retrouvé {X} kg de dispo depuis {Y}h")
- Règle 5 : notifie **chaque expéditeur qualifié** ("Nouveau trajet publié sur votre corridor habituel {ville départ} → {ville arrivée}")
- Règle 6 : notifie le **voyageur** — "Offre reçue avec départ dans moins de 48h". `NotificationDispatcher.notifyCritical` (fallback SMS) est **privé**, réservé aux événements PAYMENT_RELEASED/DELIVERY_CONFIRMED/DISPUTE_OPENED — on utilise le `notifyUser(...)` public standard (push + notification persistée), sans fallback SMS. Correction par rapport à la version initiale de ce document.

### Modèle de données additionnel

**Correction post-lecture du code réel** : `AutomationRuleEntity` n'a pas de colonne `config` dédiée — les seuils des presets sont stockés dans la colonne JSONB `action` (`Map<String,Object>`), déjà lue/écrite par `AutomationRuleService.updatePreset`/`buildPresetResponse`. Le DTO `UpdatePresetRequest.config()` (nom côté contrat API) est mappé directement sur `rule.setAction(...)` côté persistance — aucun changement de schéma nécessaire, juste consommer cette Map côté moteur.

Structure attendue par preset (clés dans la Map `action`) :

| Preset | Champs config |
|---|---|
| 1 (accepter confiance) | `minRating` (BigDecimal, défaut 4.0) |
| 2 (refuser trop lourd) | aucun — comparaison directe `bid.weightKg > announcement.availableKg` |
| 4 (capacité libérée) | `freedKgThreshold` (défaut 5), `consecutiveHours` (défaut 2) |
| 5 (expéditeurs fidèles) | aucun pour le MVP |
| 6 (dernière minute) | `hoursBeforeDeparture` (défaut 48) |

**Nouvelle table `automation_capacity_watermarks`** (migration V(n+1)) :

```sql
CREATE TABLE automation_capacity_watermarks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    announcement_id UUID NOT NULL REFERENCES announcements(id),
    free_since TIMESTAMP WITH TIME ZONE NOT NULL,
    last_alerted_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_automation_capacity_watermarks_announcement
    ON automation_capacity_watermarks(announcement_id);
```

Logique du `CapacityWatchScheduler` (toutes les 15 min) :
1. Pour chaque annonce active avec une règle 4 activée chez son voyageur, si `availableKg ≥ freedKgThreshold` :
   - Pas de watermark existant → en créer un (`free_since = now()`)
   - Watermark existant, `now() - free_since ≥ consecutiveHours` ET `last_alerted_at` absent ou antérieur à `free_since` → notifier + poser `last_alerted_at = now()` (évite de spammer à chaque run tant que la capacité reste stable)
2. Si `availableKg < freedKgThreshold`, supprimer le watermark existant (le compteur "depuis" repart à zéro à la prochaine libération).

**Corridors habituels (règle 5)** : pas de table dédiée. Dérivé à la volée via `BidRepository.findBySenderId` joint à `AnnouncementEntity.departureCity/arrivalCity`, filtré sur les bids `ACCEPTED`/complétés du voyageur courant, distinct par `(senderId, corridor)`.

**Départ (règle 6)** : `AnnouncementEntity.departureAt` (`OffsetDateTime`, champ canonique déjà utilisé pour la logique d'annulation D1/D3) — comparé à `OffsetDateTime.now()`.

### Garde-fou anti-emballement

Plafond quotidien configurable (défaut 20) d'actions automatiques (accept + reject confondus, par voyageur, toutes règles 1/2 confondues) :

- `AutomationRuleService.countTodayActions(UUID travelerId)` existe déjà (compte `automation_history` du jour courant) — le moteur l'appelle avant chaque action au lieu de recompter.
- Au-delà du plafond : la règle concernée passe `enabled=false` en base, une notification critique alerte le voyageur ("Ta règle d'automatisation a été désactivée après {N} actions aujourd'hui — vérifie ta configuration"), et l'action en cours n'est **pas** exécutée (le bid reste en attente de décision manuelle).
- Le plafond est une **constante applicative** (`DAILY_ACTION_CAP = 20`), non configurable par le voyageur, pour éviter qu'un plafond mal réglé annule sa propre protection.

### UI de configuration des seuils (dony-pro)

Ajout de champs dans `PresetRuleCard.vue` (affichés uniquement pour les presets 1, 4, 6 quand la carte est dépliée/éditée) :

- Preset 1 : slider ou input numérique "Note minimum" (1.0–5.0, pas 0.1)
- Preset 4 : deux inputs numériques "kg libres" + "heures consécutives"
- Preset 6 : input numérique "heures avant départ" (défaut 48)

Persistance via l'endpoint existant `PUT /travelers/me/automation-rules/{id}` (accepte déjà un `config` — `AutomationRuleController.java:65-72`, `UpdatePresetRequest`), aucun nouvel endpoint requis côté front.

## Tests

TDD strict, conforme CLAUDE.md (couverture ≥ 90 % viséee, mais projet déjà sous ce seuil actuellement — objectif : ne pas dégrader, viser haut sur le nouveau code).

- **Unit** : `AutomationBidListener` (les 3 combinaisons règle1/règle2/aucune, priorité refus > accept), `CapacityWatchScheduler` (création watermark, déclenchement après délai, reset si capacité repasse sous le seuil, anti-spam via `last_alerted_at`), `AutomationAnnouncementListener` (dérivation corridors fidèles), garde-fou quotidien (désactivation au plafond).
- **Intégration** : `BidService.acceptBidBySystem`/`rejectBidBySystem` (MockMvc ou test de service direct) — vérifie fermeture FULL toujours déclenchée, événements toujours publiés, historique toujours écrit.
- **Front** : tests composant `PresetRuleCard.vue` pour les nouveaux champs de config (saisie, validation, appel `PUT` avec le bon payload).

## Hors scope (explicitement)

- Règles personnalisées SI→ALORS (DSL générique)
- Règle 3 (déjà acquise, non liée au toggle)
- Flutter (dony_app) — cette feature est spécifique au back-office web voyageur pro
