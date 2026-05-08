# Design — Suppression de compte utilisateur (Flutter)

**Date :** 2026-05-06
**Statut :** Approuvé — prêt pour implémentation

---

## Contexte

Le backend expose deux endpoints pour la suppression de compte avec période de grâce de 30 jours :
- `DELETE /auth/me` → passe le compte en `PENDING_DELETION` (idempotent, bloqué si ESCROW actif → 422)
- `POST /auth/me/reactivate` → repasse en `ACTIVE` si `PENDING_DELETION` (409 sinon)

Ce design couvre l'implémentation Flutter côté client.

---

## Décisions validées

| Question | Décision |
|---|---|
| Placement "Supprimer mon compte" | Nouvel écran Settings dédié |
| Statut PENDING_DELETION au démarrage | Bannière non bloquante sur l'écran profil |
| Erreur 422 (ESCROW actif) | Dialog dédié avec lien vers les paiements |
| Architecture | Nouvelle feature `settings/` (bloc/ + data/ + presentation/) |

---

## Structure des fichiers

### Créer

```
dony_app/lib/features/settings/
├── bloc/
│   └── account_deletion_bloc.dart
├── data/
│   └── account_deletion_repository.dart
└── presentation/
    ├── settings_screen.dart
    ├── delete_account_screen.dart
    └── widgets/
        └── escrow_block_dialog.dart

dony_app/lib/features/profile/presentation/widgets/
└── pending_deletion_banner.dart
```

### Modifier

- `dony_app/lib/app/router.dart` — ajouter routes `/settings` et `/settings/delete-account`
- `dony_app/lib/features/profile/presentation/profile_screen.dart` — bouton Settings + bannière PENDING_DELETION

---

## BLoC

### Events

```dart
abstract class AccountDeletionEvent {}
class RequestDeletion extends AccountDeletionEvent {}
class ReactivateAccount extends AccountDeletionEvent {}
```

### States

```dart
abstract class AccountDeletionState {}
class AccountDeletionInitial extends AccountDeletionState {}
class AccountDeletionLoading extends AccountDeletionState {}
class AccountDeletionRequested extends AccountDeletionState {}   // → PENDING_DELETION
class AccountReactivated extends AccountDeletionState {}         // → ACTIVE
class AccountDeletionError extends AccountDeletionState {
  final String message;
  final bool isEscrowBlocked;
  AccountDeletionError({required this.message, this.isEscrowBlocked = false});
}
```

### Logique BLoC

- `RequestDeletion` → `DELETE /auth/me`
  - 204 → émet `AccountDeletionRequested`
  - 422 → émet `AccountDeletionError(isEscrowBlocked: true)`
  - autre → émet `AccountDeletionError`
- `ReactivateAccount` → `POST /auth/me/reactivate`
  - 200 → émet `AccountReactivated`
  - 409 → ignoré silencieusement (état incohérent côté client)

---

## Data Layer

### `account_deletion_repository.dart`

```dart
class AccountDeletionRepository {
  final Dio _dio;

  AccountDeletionRepository(this._dio);

  Future<void> requestDeletion() async {
    await _dio.delete('/auth/me');
  }

  Future<UserResponse> reactivateAccount() async {
    final response = await _dio.post('/auth/me/reactivate');
    return UserResponse.fromJson(response.data);
  }
}
```

Utilise le `Dio` configuré avec l'intercepteur Firebase Token existant. Pas de nouveau client HTTP.

Gestion des erreurs 422 dans le BLoC :
```dart
on DioException catch (e) {
  final isEscrow = e.response?.statusCode == 422;
  emit(AccountDeletionError(
    message: isEscrow
        ? 'Vous avez un paiement en cours.'
        : 'Une erreur est survenue.',
    isEscrowBlocked: isEscrow,
  ));
}
```

---

## UI & Navigation

### `settings_screen.dart`

Liste simple avec l'entrée "Supprimer mon compte" (texte rouge). Navigation vers `/settings/delete-account`.

### `delete_account_screen.dart`

- Titre : "Supprimer mon compte"
- Explication : période de grâce 30 jours, annonces/bids archivés immédiatement, réactivation possible avant J+30
- Bouton "Confirmer la suppression" → `RequestDeletion`
- Si `AccountDeletionRequested` → retour profil avec message de confirmation

### `escrow_block_dialog.dart`

Dialog affiché si `AccountDeletionError(isEscrowBlocked: true)` :
- Message : "Vous avez un paiement en cours (escrow). La suppression sera possible une fois la livraison confirmée."
- Bouton "Voir mes paiements" → navigation vers l'écran paiements existant
- Bouton "Fermer"

### `pending_deletion_banner.dart`

Bandeau non bloquant sur le profil si `user.status == 'PENDING_DELETION'` :
- Texte : "Votre compte sera supprimé le [date J+30 calculée depuis deletionRequestedAt]"
- Bouton "Annuler la suppression" → `ReactivateAccount`
- Si `AccountReactivated` → bannière disparaît, profil rafraîchi

### Accès au statut PENDING_DELETION

Le `UserResponse` (déjà chargé par le ProfileBloc) expose le champ `status`. Si `status == 'PENDING_DELETION'`, afficher la bannière avec `deletionRequestedAt` pour calculer la date J+30.

---

## Routes

```dart
// À ajouter dans router.dart
GoRoute(
  path: '/settings',
  builder: (context, state) => const SettingsScreen(),
  routes: [
    GoRoute(
      path: 'delete-account',
      builder: (context, state) => const DeleteAccountScreen(),
    ),
  ],
),
```

---

## Tests à créer

### `account_deletion_bloc_test.dart`
- `RequestDeletion` succès → émet `AccountDeletionRequested`
- `RequestDeletion` 422 → émet `AccountDeletionError(isEscrowBlocked: true)`
- `RequestDeletion` erreur réseau → émet `AccountDeletionError`
- `ReactivateAccount` succès → émet `AccountReactivated`
- `ReactivateAccount` 409 → ignoré (pas d'erreur émise)

### `settings_screen_test.dart`
- Affiche l'entrée "Supprimer mon compte"
- Tap → navigation vers `/settings/delete-account`

### `delete_account_screen_test.dart`
- Affiche les informations de la période de grâce
- Bouton "Confirmer" → état loading, puis retour profil si succès
- 422 → affiche `EscrowBlockDialog`

### `pending_deletion_banner_test.dart`
- Visible si `status == PENDING_DELETION`
- Invisible si `status == ACTIVE`
- Bouton "Annuler" → `ReactivateAccount` → bannière disparaît
