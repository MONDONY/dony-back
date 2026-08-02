# Brouillons de demandes et dépublication — design

**Date :** 2026-08-01
**Périmètre :** `dony-back` + `dony_app` (deux repos, deux branches, deux PR — le back d'abord)

## Problème

Trois manques, tous sur le même axe : une demande d'envoi publiée est irréversible et ses actions propriétaire sont introuvables.

1. **L'écran « Ma demande » n'offre aucune action utile.** Le bouton `…` de l'AppBar est un `onPressed: () {}` vide (`package_request_detail_screen.dart:97`). La seule action disponible est un bouton rouge « Annuler la demande ». Modifier une demande existe pourtant côté code (`PackageRequestCreateWizard.show(context, initial: …)`) mais n'est atteignable depuis nulle part sur cet écran.
2. **On ne peut pas retirer une demande de la circulation sans l'annuler.** Annuler est terminal. Un expéditeur qui veut suspendre sa demande le temps de réfléchir n'a que l'option destructive.
3. **Les demandes n'ont pas de brouillon,** alors que les trajets en ont un depuis longtemps (`AnnouncementStatus.DRAFT`, `saveAsDraft` à la création, `POST /announcements/{id}/publish`, limite free/PRO). Un expéditeur ne peut pas préparer une demande et la publier plus tard.

Le trajet a déjà résolu (3) et sait le faire proprement. La demande doit converger vers ce modèle plutôt que d'inventer le sien. Personne ne peut aujourd'hui dépublier — ni trajet ni demande.

## Objectif

Amener la demande d'envoi à parité avec le trajet sur les brouillons, ajouter la dépublication aux **deux** domaines, et refondre « Ma demande » pour que ces actions soient visibles au lieu d'être cachées derrière un menu mort.

Règle transverse : **on ne dépublie que tant que rien n'a été reçu** — zéro offre pour une demande, zéro demande reçue pour un trajet. Au-delà, des tiers se sont engagés et le retrait unilatéral ne leur est pas opposable.

---

## 1. Backend — statut `DRAFT` sur les demandes

### Migration `V185__package_requests_draft_status.sql`

`package_requests.status` est un `VARCHAR(20)` sous contrainte CHECK explicite :

```sql
-- V57__package_requests.sql:48
CONSTRAINT chk_pkg_req_status CHECK (
  status IN ('OPEN', 'NEGOTIATING', 'ACCEPTED', 'EXPIRED', 'CANCELLED', 'COMPLETED')
)
```

La migration supprime puis recrée la contrainte en y ajoutant `DRAFT`. Aucune colonne n'est ajoutée : le piège connu « colonne NOT NULL absente du DDL H2 » ne s'applique pas ici.

### Enum et création

`PackageRequestStatus` gagne `DRAFT` (en tête, comme `AnnouncementStatus`).

`PackageRequestCreateRequest` gagne `Boolean saveAsDraft` (défaut `false`, symétrique de `AnnouncementRequest`).

`PackageRequestService.createAndReturnEntity` se scinde selon `saveAsDraft` :

| | Brouillon | Publication directe (inchangé) |
|---|---|---|
| Statut posé | `DRAFT` | `OPEN` |
| KYC vérifié exigé | non | oui |
| `maxOpenRequestsPerSender` | non compté | compté |
| Limite brouillons | oui | — |
| `disclaimerSignedAt` | non posé | posé |
| `PackageRequestCreatedEvent` | **non publié** | publié |
| Audit | `DRAFT_CREATED` | `CREATED` |

Deux points sont structurants, pas cosmétiques :

- **L'event ne doit pas partir sur un brouillon.** `PackageRequestCreatedEvent` déclenche les alertes corridor vers les voyageurs. Le publier sur un brouillon notifierait une demande que personne ne peut voir.
- **Le disclaimer douanier se signe à la publication**, pas à la rédaction. Le code actuel le pose à la création parce que création = publication ; la distinction apparaît maintenant.

Le KYC n'est pas exigé sur un brouillon, exactement comme pour le trajet (`assertCanPublish` n'est appelé que si `!isDraft`). Cela permet de préparer une demande pendant que la vérification d'identité est en cours.

### Limite de brouillons

Réutilisation de `YadonyConfigProperties.Limits` (`maxDrafts` = 1, `maxDraftsPro` = 10), le même pool que les trajets — un utilisateur a un quota de brouillons, pas un quota par type d'objet. Dépassement → `403 draft-limit-reached`, même code d'erreur que le trajet.

`PackageRequestService` a déjà un champ `config` de type `RequestsConfig`. Le nouveau bean s'injecte donc sous un nom distinct : `yadonyConfig`.

Le comptage porte sur `countBySenderIdAndStatus(senderId, DRAFT)` — nouvelle méthode de `PackageRequestRepository`.

### `POST /package-requests/{id}/publish`

`DRAFT → OPEN`. Rejoue **toutes** les validations de publication, dans cet ordre :

1. ownership (sinon `404 request/not-found` — on ne révèle pas l'existence)
2. statut `DRAFT` attendu (sinon `409 request/not-draft`)
3. KYC vérifié (sinon `403 kyc/not-verified`)
4. corridor valide, date ≤ 90 jours, budget présent si prix ferme
5. `maxOpenRequestsPerSender` (sinon `409 request/max-open-reached`)

Puis : statut `OPEN`, `disclaimerSignedAt = now`, publication de `PackageRequestCreatedEvent`, audit `PUBLISHED`.

Les validations 4 sont rejouées et non supposées acquises : les données peuvent avoir été modifiées depuis la création du brouillon, et une date passe naturellement hors fenêtre avec le temps.

### Visibilité

- **Recherche publique** : rien à faire. `PackageRequestSpecifications:16` filtre déjà sur `OPEN, NEGOTIATING` — `DRAFT` est exclu par construction.
- **`getById`** : le champ `isPubliclyListed` (ligne 291) doit traiter `DRAFT` comme non listé, et un non-propriétaire reçoit `404 request/not-found` — jamais `403`, qui révélerait l'existence de la demande.

### Modification d'un brouillon

`update()` accepte aujourd'hui `OPEN` et `NEGOTIATING` ; il faut y ajouter `DRAFT`.

**Piège :** `update()` fait `entity.setStatus(PackageRequestStatus.OPEN)` en dur (ligne 260) pour repasser en `OPEN` une demande qui était en négociation. Sur un brouillon, cette ligne le publierait silencieusement. Elle doit être conditionnée : un `DRAFT` reste `DRAFT` après édition.

### Suppression

`DELETE /package-requests/{id}` existe et fait un soft delete. Un brouillon est supprimable par ce chemin, sans changement.

---

## 2. Backend — dépublication des deux domaines

Deux endpoints symétriques, tous deux réservés au propriétaire.

### `POST /package-requests/{id}/unpublish`

`OPEN → DRAFT`. Refusé si :

- le statut n'est pas `OPEN` → `409 request/not-unpublishable` (couvre `NEGOTIATING`, `ACCEPTED`, `COMPLETED`, `EXPIRED`, `CANCELLED`)
- au moins un `NegotiationThread` existe pour cette demande → `409 request/has-offers`

Le second test est distinct du premier et ne s'y réduit pas : un thread peut exister alors que la demande est encore `OPEN`.

### `POST /announcements/{id}/unpublish`

`ACTIVE → DRAFT`. Refusé si le statut n'est pas `ACTIVE` (`409 announcement/not-unpublishable`) ou si `bidsCount > 0` (`409 announcement/has-bids`).

### Règles communes

- La **limite de brouillons s'applique** à la dépublication. Sans ce contrôle, dépublier devient un contournement du plafond. Conséquence assumée : un compte free ayant déjà un brouillon reçoit `403 draft-limit-reached` et doit d'abord traiter ce brouillon.
- Audit : `UNPUBLISHED` sur l'entité concernée.
- Aucun nettoyage de favoris n'est fait : un objet redevenu brouillon est invisible des tiers, et un favori pointant dessus renvoie 404 — c'est déjà le comportement des trajets `DRAFT`, on ne crée pas de règle nouvelle.

---

## 3. Flutter — brouillon dans le wizard de demande

`PackageRequestPreviewSheet`, calquée sur `AnnouncementPreviewSheet`. L'étape 3 du wizard voit son CTA passer de « Publier ma demande » à « Aperçu » ; la sheet récapitule la demande et porte les deux sorties : « Publier ma demande » et « Enregistrer en brouillon ».

Chaîne : `FormStep3Submitted` gagne `saveAsDraft` → `PackageRequestFormBloc` → repository → datasource (`saveAsDraft: true` dans le body, omis sinon, comme le trajet).

Succès brouillon → `DonySuccessScreen` en variante « Brouillon enregistré » / « Tu pourras le publier quand tu veux. », CTA vers le détail de la demande.

Le `403 draft-limit-reached` est présenté comme sur le trajet (dialogue « Limite de brouillons atteinte », cf. `create_trip_screen.dart:1561`).

Les boutons de la sheet respectent la règle projet : jamais de `DonyButton` dans le `child` scrollable, toujours dans `stickyBottom`.

## 4. Flutter — refonte « Ma demande »

`RequestOwnerActionGrid`, calquée sur `OwnerActionGrid` (grille 2 colonnes, tuiles icône + label, tuiles désactivées grisées avec tooltip explicatif) :

| Tuile | Condition d'affichage | Condition d'activation |
|---|---|---|
| **Publier** | statut `DRAFT` | toujours |
| **Modifier** | toujours | statuts `DRAFT`, `OPEN`, `NEGOTIATING` |
| **Dépublier** | statut `OPEN` | zéro offre reçue |
| **Annuler** | statuts `OPEN`, `NEGOTIATING` | toujours |

« Zéro offre reçue » se lit sur la liste de threads déjà chargée par l'écran (`_threads`), pas sur un nouveau champ du modèle : l'information est là, il suffit de la passer à la grille. Le backend reste l'autorité — il renvoie `409 request/has-offers` si l'état a changé entre-temps.

Le bouton `…` de l'AppBar est **supprimé**. C'est lui l'action morte : la grille le remplace, on ne le remplit pas.

Le bouton rouge sticky « Annuler la demande » disparaît au profit de la tuile Annuler. **Correction par rapport à l'échange précédent :** l'action actuelle n'a en réalité *aucune* confirmation — le tap annule immédiatement. La tuile « Supprimer » du trajet (`owner_action_grid.dart:138-154`), elle, confirme via `DonyDialog.show(..., variant: DonyDialogVariant.destructive)`. La tuile Annuler de la demande adopte ce même garde-fou : c'est la même refonte qui la fait cohabiter avec des tuiles qui confirment déjà, l'incohérence serait immédiatement visible.

**L'édition ne se durcit pas.** Le trajet désactive « Modifier » dès qu'une demande existe ; la demande d'envoi, elle, sait déjà s'éditer en cours de négociation (les offres ouvertes sont auto-rejetées côté backend et l'utilisateur en est averti par `PackageRequestCreateWizard.requiresEditWarning`). Ce comportement est conservé — l'aligner sur le trajet serait une régression fonctionnelle.

### Les deux chemins vers « Ma demande »

L'écran existe en deux exemplaires dans le même fichier, et les deux doivent recevoir la grille :

- `PackageRequestDetailScreen` — plein écran, atteint par route (`/package-requests/{id}`)
- `PackageRequestDetailBottomSheet` — sheet, **chemin principal** : c'est ce qui s'ouvre au tap sur une demande de la liste (`my_package_requests_screen.dart:444`)

Les deux dupliquent déjà le même corps (chargement, hero card, section offres) et la même logique d'annulation. Le corps commun est extrait dans un widget unique paramétré par la demande et ses threads, consommé par les deux enveloppes. Sans cette extraction, la grille devrait être écrite deux fois et les deux copies divergeraient.

Effet de bord bienvenu : la mécanique `_SheetBtnConfig` / `ValueNotifier` de la sheet n'existait que pour porter le bouton « Annuler » en sticky. L'annulation passant dans la grille, cette mécanique disparaît.

Le hero card et la section offres sont conservés tels quels ; la grille s'insère entre les deux.

## 5. Flutter — brouillons dans les listes

Traitement identique à celui des trajets, sans nouvelle convention :

- `PackageRequestStatus.draft` dans le modèle, badge « Brouillon » sur la carte (cf. `trip_card.dart:57`)
- `RequestQuickFilter.draft` + chip « Brouillons » dans `_FilterRow`, avec compteur
- tri : brouillons en tête de liste (cf. `announcement_list_screen.dart:19`)
- état vide dédié : « Aucun brouillon »

**Piège :** `isSearchRequest` (`request_filter_cubit.dart:36`) exclut les statuts qui ne sont pas « en recherche ». `DRAFT` doit y être **inclus**, sinon les brouillons n'apparaissent dans aucune liste et deviennent inatteignables.

## 6. Flutter — dépublication du trajet

Une tuile « Dépublier » s'ajoute à `OwnerActionGrid`, visible si le trajet est `ACTIVE`, activée si zéro demande reçue, désactivée sinon avec le tooltip « Dépublier n'est possible qu'avant la première demande ».

Le compteur de demandes est celui que la grille utilise déjà pour le gating de « Modifier » : `bidsCount` du modèle, affiné par `BidBloc` quand la liste a répondu (cf. `owner_action_grid.dart:51-58`).

---

## Tests

**Backend** — `PackageRequestServiceTest` : création en brouillon (statut, absence d'event, absence de disclaimer), limite de brouillons free et PRO, publication (chaque validation rejouée, event émis, disclaimer posé), publication d'un non-brouillon rejetée, dépublication nominale, dépublication refusée avec offre, édition d'un brouillon qui reste brouillon, brouillon invisible d'un tiers (404). `AnnouncementServiceTest` : dépublication nominale, refus avec demandes, refus hors `ACTIVE`, limite de brouillons. Tests d'intégration `MockMvc` sur les quatre endpoints (deux publish/unpublish côté demande, unpublish côté trajet), incluant les cas non-propriétaire. Test de migration V185.

**Flutter** — bloc : `FormStep3Submitted(saveAsDraft: true)` transmet bien le drapeau, gestion du 403. Widgets : `RequestOwnerActionGrid` (chaque combinaison statut × nombre d'offres), corps commun rendu à l'identique dans l'écran plein et dans la sheet, `PackageRequestPreviewSheet` (deux sorties), filtre « Brouillons », badge sur la carte, tuile « Dépublier » du trajet.

Les tests widget existants qui assertent les libellés de « Ma demande » (notamment le bouton « Annuler la demande » qui devient une tuile) doivent être mis à jour, pas contournés.

Couverture ≥ 90 % sur les deux projets, conformément à la politique de test du projet.

## Séquencement

1. Backend : migration, enum, création en brouillon, publication, dépublication des deux domaines, tests.
2. Flutter : modèle et statut, sheet d'aperçu + brouillon, refonte « Ma demande », listes, dépublication du trajet, tests.

Le front dépend des endpoints du back — l'ordre n'est pas réversible.
