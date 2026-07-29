# Flutter Account Deletion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement account deletion with 30-day grace period in the Flutter app (yadony_app), mirroring the backend endpoints `DELETE /auth/me` and `POST /auth/me/reactivate`.

**Architecture:** New `features/settings/` feature (bloc/ + data/ + presentation/) following the project's feature-first pattern. Profile screen gains a non-blocking `PendingDeletionBanner` when `status == 'PENDING_DELETION'`. All navigation uses GoRouter (`context.push`/`context.pop`), all business state uses BLoC — no `setState`.

**Tech Stack:** Flutter, BLoC (`flutter_bloc`), GoRouter, Dio (`ApiClient`), GetIt DI, `bloc_test` + `mocktail` for tests.

---

## File Map

### Create
- `yadony_app/lib/features/settings/bloc/account_deletion_bloc.dart`
- `yadony_app/lib/features/settings/bloc/account_deletion_event.dart`
- `yadony_app/lib/features/settings/bloc/account_deletion_state.dart`
- `yadony_app/lib/features/settings/data/account_deletion_repository.dart`
- `yadony_app/lib/features/settings/presentation/settings_screen.dart`
- `yadony_app/lib/features/settings/presentation/delete_account_screen.dart`
- `yadony_app/lib/features/settings/presentation/widgets/escrow_block_dialog.dart`
- `yadony_app/lib/features/profile/presentation/widgets/pending_deletion_banner.dart`
- `yadony_app/test/features/settings/bloc/account_deletion_bloc_test.dart`
- `yadony_app/test/features/settings/data/account_deletion_repository_test.dart`
- `yadony_app/test/features/settings/presentation/delete_account_screen_test.dart`
- `yadony_app/test/features/settings/presentation/settings_screen_test.dart`
- `yadony_app/test/features/profile/presentation/widgets/pending_deletion_banner_test.dart`

### Modify
- `yadony_app/lib/features/auth/data/models/user_model.dart` — add `deletionRequestedAt`, `isPendingDeletion`
- `yadony_app/lib/core/di/injection.dart` — register `AccountDeletionRepository` + `AccountDeletionBloc`
- `yadony_app/lib/app/router.dart` — add `/settings` and `/settings/delete-account` routes; wrap `/profile` with `AccountDeletionBloc`
- `yadony_app/lib/features/profile/presentation/profile_screen.dart` — add Settings tile + `PendingDeletionBanner`
- `yadony_app/test/features/auth/data/models/user_model_test.dart` — add `deletionRequestedAt` test

---

## Task 1: Git branch + UserModel extension

**Files:**
- Create branch: `feat/flutter-account-deletion`
- Modify: `yadony_app/lib/features/auth/data/models/user_model.dart`
- Modify: `yadony_app/test/features/auth/data/models/user_model_test.dart`

- [ ] **Step 1: Create the branch**

```bash
cd /home/a-diakite/Desktop/MyProject/my_app
git checkout -b feat/flutter-account-deletion
```

Expected: `Switched to a new branch 'feat/flutter-account-deletion'`

- [ ] **Step 2: Write the failing test**

Open `yadony_app/test/features/auth/data/models/user_model_test.dart` and add this test group (file already exists — add inside `main()`):

```dart
group('deletionRequestedAt', () {
  test('parses deletionRequestedAt when present', () {
    final json = {
      'id': 'u1',
      'roles': ['SENDER'],
      'kycStatus': 'PENDING',
      'status': 'PENDING_DELETION',
      'deletionRequestedAt': '2026-05-06T10:00:00Z',
    };
    final user = UserModel.fromJson(json);
    expect(user.deletionRequestedAt, isNotNull);
    expect(user.deletionRequestedAt!.year, 2026);
    expect(user.isPendingDeletion, isTrue);
  });

  test('deletionRequestedAt is null when absent', () {
    final json = {
      'id': 'u1',
      'roles': ['SENDER'],
      'kycStatus': 'PENDING',
      'status': 'ACTIVE',
    };
    final user = UserModel.fromJson(json);
    expect(user.deletionRequestedAt, isNull);
    expect(user.isPendingDeletion, isFalse);
  });
});
```

- [ ] **Step 3: Run to verify it fails**

```bash
cd /home/a-diakite/Desktop/MyProject/my_app/yadony_app
flutter test test/features/auth/data/models/user_model_test.dart
```

Expected: FAIL — `deletionRequestedAt` not found on `UserModel`.

- [ ] **Step 4: Implement the changes in UserModel**

In `yadony_app/lib/features/auth/data/models/user_model.dart`, make these changes:

Add field after `stripeAccountStatus`:
```dart
final DateTime? deletionRequestedAt;
```

Add to constructor:
```dart
this.deletionRequestedAt,
```

Add to `fromJson` after `stripeAccountStatus`:
```dart
deletionRequestedAt: json['deletionRequestedAt'] != null
    ? DateTime.tryParse(json['deletionRequestedAt'] as String)
    : null,
```

Add getter after `isTraveler`:
```dart
bool get isPendingDeletion => status == 'PENDING_DELETION';
```

Add to `props` list:
```dart
deletionRequestedAt,
```

- [ ] **Step 5: Run to verify it passes**

```bash
flutter test test/features/auth/data/models/user_model_test.dart
```

Expected: All tests PASS.

- [ ] **Step 6: Commit**

```bash
cd /home/a-diakite/Desktop/MyProject/my_app/yadony_app
git add lib/features/auth/data/models/user_model.dart \
        test/features/auth/data/models/user_model_test.dart
git commit -m "feat(flutter): add deletionRequestedAt + isPendingDeletion to UserModel"
```

---

## Task 2: AccountDeletionRepository

**Files:**
- Create: `yadony_app/lib/features/settings/data/account_deletion_repository.dart`
- Create: `yadony_app/test/features/settings/data/account_deletion_repository_test.dart`

- [ ] **Step 1: Create the test file**

```dart
// yadony_app/test/features/settings/data/account_deletion_repository_test.dart
import 'package:yadony/core/error/app_exception.dart';
import 'package:yadony/core/network/api_client.dart';
import 'package:yadony/features/settings/data/account_deletion_repository.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

class MockApiClient extends Mock implements ApiClient {}
class MockDio extends Mock implements Dio {}

void main() {
  late MockApiClient mockClient;
  late MockDio mockDio;
  late AccountDeletionRepository repo;

  setUp(() {
    mockClient = MockApiClient();
    mockDio = MockDio();
    when(() => mockClient.dio).thenReturn(mockDio);
    repo = AccountDeletionRepository(mockClient);
  });

  group('requestDeletion', () {
    test('completes when DELETE /auth/me returns 204', () async {
      when(() => mockDio.delete<dynamic>('/auth/me'))
          .thenAnswer((_) async => Response(
                requestOptions: RequestOptions(path: '/auth/me'),
                statusCode: 204,
              ));

      await expectLater(repo.requestDeletion(), completes);
    });

    test('throws ValidationException when 422', () async {
      when(() => mockDio.delete<dynamic>('/auth/me')).thenThrow(
        DioException(
          requestOptions: RequestOptions(path: '/auth/me'),
          error: const ValidationException('active-transactions'),
          type: DioExceptionType.badResponse,
        ),
      );

      await expectLater(
        repo.requestDeletion(),
        throwsA(isA<ValidationException>()),
      );
    });
  });

  group('reactivateAccount', () {
    test('returns UserModel on success', () async {
      when(() => mockDio.post<dynamic>('/auth/me/reactivate'))
          .thenAnswer((_) async => Response(
                requestOptions: RequestOptions(path: '/auth/me/reactivate'),
                statusCode: 200,
                data: {
                  'id': 'u1',
                  'roles': ['SENDER'],
                  'kycStatus': 'PENDING',
                  'status': 'ACTIVE',
                },
              ));

      final user = await repo.reactivateAccount();
      expect(user.id, 'u1');
      expect(user.status, 'ACTIVE');
    });

    test('throws AppException on network error', () async {
      when(() => mockDio.post<dynamic>('/auth/me/reactivate')).thenThrow(
        DioException(
          requestOptions: RequestOptions(path: '/auth/me/reactivate'),
          error: const NetworkException('Network error'),
          type: DioExceptionType.unknown,
        ),
      );

      await expectLater(
        repo.reactivateAccount(),
        throwsA(isA<AppException>()),
      );
    });
  });
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd /home/a-diakite/Desktop/MyProject/my_app/yadony_app
flutter test test/features/settings/data/account_deletion_repository_test.dart
```

Expected: FAIL — `AccountDeletionRepository` does not exist.

- [ ] **Step 3: Implement the repository**

Create `yadony_app/lib/features/settings/data/account_deletion_repository.dart`:

```dart
import 'package:yadony/core/error/app_exception.dart';
import 'package:yadony/core/network/api_client.dart';
import 'package:yadony/features/auth/data/models/user_model.dart';

class AccountDeletionRepository {
  final ApiClient _client;

  AccountDeletionRepository(this._client);

  Future<void> requestDeletion() async {
    try {
      await _client.dio.delete('/auth/me');
    } catch (e) {
      throw unwrapDioError(e);
    }
  }

  Future<UserModel> reactivateAccount() async {
    try {
      final response = await _client.dio.post('/auth/me/reactivate');
      return UserModel.fromJson(response.data as Map<String, dynamic>);
    } catch (e) {
      throw unwrapDioError(e);
    }
  }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
flutter test test/features/settings/data/account_deletion_repository_test.dart
```

Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add yadony_app/lib/features/settings/data/account_deletion_repository.dart \
        yadony_app/test/features/settings/data/account_deletion_repository_test.dart
git commit -m "feat(flutter): add AccountDeletionRepository"
```

---

## Task 3: AccountDeletionBloc

**Files:**
- Create: `yadony_app/lib/features/settings/bloc/account_deletion_event.dart`
- Create: `yadony_app/lib/features/settings/bloc/account_deletion_state.dart`
- Create: `yadony_app/lib/features/settings/bloc/account_deletion_bloc.dart`
- Create: `yadony_app/test/features/settings/bloc/account_deletion_bloc_test.dart`

- [ ] **Step 1: Write the failing test**

Create `yadony_app/test/features/settings/bloc/account_deletion_bloc_test.dart`:

```dart
import 'package:bloc_test/bloc_test.dart';
import 'package:yadony/core/error/app_exception.dart';
import 'package:yadony/features/auth/data/models/user_model.dart';
import 'package:yadony/features/settings/bloc/account_deletion_bloc.dart';
import 'package:yadony/features/settings/data/account_deletion_repository.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

class MockAccountDeletionRepository extends Mock
    implements AccountDeletionRepository {}

const _activeUser = UserModel(
  id: 'u1',
  roles: ['SENDER'],
  kycStatus: 'PENDING',
  status: 'ACTIVE',
);

void main() {
  late MockAccountDeletionRepository mockRepo;
  late AccountDeletionBloc bloc;

  setUp(() {
    mockRepo = MockAccountDeletionRepository();
    bloc = AccountDeletionBloc(mockRepo);
  });

  tearDown(() => bloc.close());

  test('initial state is AccountDeletionInitial', () {
    expect(bloc.state, isA<AccountDeletionInitial>());
  });

  group('RequestDeletion', () {
    blocTest<AccountDeletionBloc, AccountDeletionState>(
      'emits [Loading, AccountDeletionRequested] on success',
      build: () {
        when(() => mockRepo.requestDeletion()).thenAnswer((_) async {});
        return bloc;
      },
      act: (b) => b.add(const RequestDeletion()),
      expect: () => [
        isA<AccountDeletionLoading>(),
        isA<AccountDeletionRequested>(),
      ],
      verify: (_) => verify(() => mockRepo.requestDeletion()).called(1),
    );

    blocTest<AccountDeletionBloc, AccountDeletionState>(
      'emits [Loading, AccountDeletionError(isEscrowBlocked: true)] on ValidationException',
      build: () {
        when(() => mockRepo.requestDeletion())
            .thenThrow(const ValidationException('active-transactions'));
        return bloc;
      },
      act: (b) => b.add(const RequestDeletion()),
      expect: () => [
        isA<AccountDeletionLoading>(),
        isA<AccountDeletionError>().having(
          (s) => s.isEscrowBlocked,
          'isEscrowBlocked',
          isTrue,
        ),
      ],
    );

    blocTest<AccountDeletionBloc, AccountDeletionState>(
      'emits [Loading, AccountDeletionError] on generic AppException',
      build: () {
        when(() => mockRepo.requestDeletion())
            .thenThrow(const NetworkException('Erreur réseau'));
        return bloc;
      },
      act: (b) => b.add(const RequestDeletion()),
      expect: () => [
        isA<AccountDeletionLoading>(),
        isA<AccountDeletionError>().having(
          (s) => s.isEscrowBlocked,
          'isEscrowBlocked',
          isFalse,
        ),
      ],
    );

    blocTest<AccountDeletionBloc, AccountDeletionState>(
      'emits [Loading, AccountDeletionError] on unexpected exception',
      build: () {
        when(() => mockRepo.requestDeletion()).thenThrow(Exception('Oops'));
        return bloc;
      },
      act: (b) => b.add(const RequestDeletion()),
      expect: () => [
        isA<AccountDeletionLoading>(),
        isA<AccountDeletionError>(),
      ],
    );
  });

  group('ReactivateAccount', () {
    blocTest<AccountDeletionBloc, AccountDeletionState>(
      'emits [Loading, AccountReactivated] on success',
      build: () {
        when(() => mockRepo.reactivateAccount())
            .thenAnswer((_) async => _activeUser);
        return bloc;
      },
      act: (b) => b.add(const ReactivateAccount()),
      expect: () => [
        isA<AccountDeletionLoading>(),
        isA<AccountReactivated>().having(
          (s) => s.user.status,
          'user.status',
          'ACTIVE',
        ),
      ],
      verify: (_) => verify(() => mockRepo.reactivateAccount()).called(1),
    );

    blocTest<AccountDeletionBloc, AccountDeletionState>(
      'emits [Loading, AccountDeletionError] on AppException',
      build: () {
        when(() => mockRepo.reactivateAccount())
            .thenThrow(const NetworkException('Erreur réseau'));
        return bloc;
      },
      act: (b) => b.add(const ReactivateAccount()),
      expect: () => [
        isA<AccountDeletionLoading>(),
        isA<AccountDeletionError>(),
      ],
    );
  });
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd /home/a-diakite/Desktop/MyProject/my_app/yadony_app
flutter test test/features/settings/bloc/account_deletion_bloc_test.dart
```

Expected: FAIL — `AccountDeletionBloc` does not exist.

- [ ] **Step 3: Create the event file**

Create `yadony_app/lib/features/settings/bloc/account_deletion_event.dart`:

```dart
part of 'account_deletion_bloc.dart';

sealed class AccountDeletionEvent extends Equatable {
  const AccountDeletionEvent();

  @override
  List<Object?> get props => [];
}

class RequestDeletion extends AccountDeletionEvent {
  const RequestDeletion();
}

class ReactivateAccount extends AccountDeletionEvent {
  const ReactivateAccount();
}
```

- [ ] **Step 4: Create the state file**

Create `yadony_app/lib/features/settings/bloc/account_deletion_state.dart`:

```dart
part of 'account_deletion_bloc.dart';

sealed class AccountDeletionState extends Equatable {
  const AccountDeletionState();

  @override
  List<Object?> get props => [];
}

class AccountDeletionInitial extends AccountDeletionState {
  const AccountDeletionInitial();
}

class AccountDeletionLoading extends AccountDeletionState {
  const AccountDeletionLoading();
}

class AccountDeletionRequested extends AccountDeletionState {
  const AccountDeletionRequested();
}

class AccountReactivated extends AccountDeletionState {
  final UserModel user;
  const AccountReactivated(this.user);

  @override
  List<Object?> get props => [user];
}

class AccountDeletionError extends AccountDeletionState {
  final String message;
  final bool isEscrowBlocked;

  const AccountDeletionError({
    required this.message,
    this.isEscrowBlocked = false,
  });

  @override
  List<Object?> get props => [message, isEscrowBlocked];
}
```

- [ ] **Step 5: Create the BLoC file**

Create `yadony_app/lib/features/settings/bloc/account_deletion_bloc.dart`:

```dart
import 'package:yadony/core/error/app_exception.dart';
import 'package:yadony/features/auth/data/models/user_model.dart';
import 'package:yadony/features/settings/data/account_deletion_repository.dart';
import 'package:equatable/equatable.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

part 'account_deletion_event.dart';
part 'account_deletion_state.dart';

class AccountDeletionBloc
    extends Bloc<AccountDeletionEvent, AccountDeletionState> {
  final AccountDeletionRepository _repository;

  AccountDeletionBloc(this._repository) : super(const AccountDeletionInitial()) {
    on<RequestDeletion>(_onRequestDeletion);
    on<ReactivateAccount>(_onReactivateAccount);
  }

  Future<void> _onRequestDeletion(
    RequestDeletion event,
    Emitter<AccountDeletionState> emit,
  ) async {
    emit(const AccountDeletionLoading());
    try {
      await _repository.requestDeletion();
      emit(const AccountDeletionRequested());
    } on ValidationException {
      emit(const AccountDeletionError(
        message:
            'Vous avez un paiement en cours. La suppression sera possible une fois la livraison confirmée.',
        isEscrowBlocked: true,
      ));
    } on AppException catch (e) {
      emit(AccountDeletionError(message: e.message));
    } catch (_) {
      emit(const AccountDeletionError(
          message: 'Une erreur est survenue. Veuillez réessayer.'));
    }
  }

  Future<void> _onReactivateAccount(
    ReactivateAccount event,
    Emitter<AccountDeletionState> emit,
  ) async {
    emit(const AccountDeletionLoading());
    try {
      final user = await _repository.reactivateAccount();
      emit(AccountReactivated(user));
    } on AppException catch (e) {
      emit(AccountDeletionError(message: e.message));
    } catch (_) {
      emit(const AccountDeletionError(
          message: 'Une erreur est survenue. Veuillez réessayer.'));
    }
  }
}
```

- [ ] **Step 6: Run to verify tests pass**

```bash
flutter test test/features/settings/bloc/account_deletion_bloc_test.dart
```

Expected: All 7 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add yadony_app/lib/features/settings/bloc/ \
        yadony_app/test/features/settings/bloc/account_deletion_bloc_test.dart
git commit -m "feat(flutter): add AccountDeletionBloc with RequestDeletion + ReactivateAccount events"
```

---

## Task 4: Dependency Injection registration

**Files:**
- Modify: `yadony_app/lib/core/di/injection.dart`

- [ ] **Step 1: Add imports at top of injection.dart**

Add these imports alongside the existing ones:

```dart
import 'package:yadony/features/settings/bloc/account_deletion_bloc.dart';
import 'package:yadony/features/settings/data/account_deletion_repository.dart';
```

- [ ] **Step 2: Register the dependencies**

Inside `setupDependencies()`, add a Settings section after the Profile section (look for `ProfileRepository`):

```dart
// Settings — Account Deletion
getIt.registerLazySingleton<AccountDeletionRepository>(
  () => AccountDeletionRepository(getIt<ApiClient>()),
);
getIt.registerFactory<AccountDeletionBloc>(
  () => AccountDeletionBloc(getIt<AccountDeletionRepository>()),
);
```

- [ ] **Step 3: Run all tests to verify nothing is broken**

```bash
cd /home/a-diakite/Desktop/MyProject/my_app/yadony_app
flutter test
```

Expected: All tests PASS.

- [ ] **Step 4: Commit**

```bash
git add yadony_app/lib/core/di/injection.dart
git commit -m "feat(flutter): register AccountDeletionRepository and AccountDeletionBloc in DI"
```

---

## Task 5: PendingDeletionBanner widget

**Files:**
- Create: `yadony_app/lib/features/profile/presentation/widgets/pending_deletion_banner.dart`
- Create: `yadony_app/test/features/profile/presentation/widgets/pending_deletion_banner_test.dart`

- [ ] **Step 1: Write the failing test**

Create `yadony_app/test/features/profile/presentation/widgets/pending_deletion_banner_test.dart`:

```dart
import 'package:yadony/features/profile/presentation/widgets/pending_deletion_banner.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Widget _wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

void main() {
  final deletionDate = DateTime(2026, 5, 6, 10, 0, 0);

  testWidgets('shows deletion date (J+30)', (tester) async {
    await tester.pumpWidget(_wrap(
      PendingDeletionBanner(
        deletionRequestedAt: deletionDate,
        onReactivate: () {},
      ),
    ));

    // J+30 = 05/06/2026
    expect(find.textContaining('05/06/2026'), findsOneWidget);
  });

  testWidgets('shows cancel button', (tester) async {
    await tester.pumpWidget(_wrap(
      PendingDeletionBanner(
        deletionRequestedAt: deletionDate,
        onReactivate: () {},
      ),
    ));

    expect(find.text('Annuler la suppression'), findsOneWidget);
  });

  testWidgets('calls onReactivate when cancel button tapped', (tester) async {
    var called = false;
    await tester.pumpWidget(_wrap(
      PendingDeletionBanner(
        deletionRequestedAt: deletionDate,
        onReactivate: () => called = true,
      ),
    ));

    await tester.tap(find.text('Annuler la suppression'));
    await tester.pump();

    expect(called, isTrue);
  });
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
flutter test test/features/profile/presentation/widgets/pending_deletion_banner_test.dart
```

Expected: FAIL — file not found.

- [ ] **Step 3: Create the widget**

Create `yadony_app/lib/features/profile/presentation/widgets/pending_deletion_banner.dart`:

```dart
import 'package:yadony/core/design/design_system.dart';
import 'package:flutter/material.dart';

class PendingDeletionBanner extends StatelessWidget {
  final DateTime deletionRequestedAt;
  final VoidCallback onReactivate;

  const PendingDeletionBanner({
    super.key,
    required this.deletionRequestedAt,
    required this.onReactivate,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    final deletionDate = deletionRequestedAt.add(const Duration(days: 30));
    final d = deletionDate.day.toString().padLeft(2, '0');
    final m = deletionDate.month.toString().padLeft(2, '0');
    final y = deletionDate.year;

    return Container(
      padding: const EdgeInsets.all(YadonySpacing.base),
      decoration: BoxDecoration(
        color: YadonyColors.errorLight,
        borderRadius: BorderRadius.circular(YadonyRadius.card),
        border: Border.all(color: YadonyColors.error.withValues(alpha: 0.25)),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(YadonySpacing.sm),
            decoration: BoxDecoration(
              color: YadonyColors.error.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(YadonyRadius.md),
            ),
            child: const Icon(
              Icons.warning_amber_rounded,
              color: YadonyColors.error,
              size: 18,
            ),
          ),
          const SizedBox(width: YadonySpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Suppression planifiée le $d/$m/$y',
                  style: tt.bodyMedium?.copyWith(
                    fontWeight: FontWeight.w700,
                    color: cs.onSurface,
                  ),
                ),
                TextButton(
                  style: TextButton.styleFrom(
                    padding: EdgeInsets.zero,
                    minimumSize: Size.zero,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  onPressed: onReactivate,
                  child: Text(
                    'Annuler la suppression',
                    style: tt.bodySmall?.copyWith(
                      color: YadonyColors.error,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
```

- [ ] **Step 4: Run to verify tests pass**

```bash
flutter test test/features/profile/presentation/widgets/pending_deletion_banner_test.dart
```

Expected: All 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add yadony_app/lib/features/profile/presentation/widgets/pending_deletion_banner.dart \
        yadony_app/test/features/profile/presentation/widgets/pending_deletion_banner_test.dart
git commit -m "feat(flutter): add PendingDeletionBanner widget"
```

---

## Task 6: EscrowBlockDialog widget

**Files:**
- Create: `yadony_app/lib/features/settings/presentation/widgets/escrow_block_dialog.dart`
- Create: `yadony_app/test/features/settings/presentation/widgets/escrow_block_dialog_test.dart`

- [ ] **Step 1: Write the failing test**

Create `yadony_app/test/features/settings/presentation/widgets/escrow_block_dialog_test.dart`:

```dart
import 'package:yadony/features/settings/presentation/widgets/escrow_block_dialog.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

Widget _wrap() => MaterialApp.router(
      routerConfig: GoRouter(
        routes: [
          GoRoute(
            path: '/',
            builder: (_, __) => Scaffold(
              body: Builder(
                builder: (ctx) => TextButton(
                  onPressed: () => showDialog(
                    context: ctx,
                    builder: (_) => const EscrowBlockDialog(),
                  ),
                  child: const Text('Open'),
                ),
              ),
            ),
          ),
          GoRoute(
            path: '/announcements',
            builder: (_, __) => const Scaffold(body: Text('Announcements')),
          ),
        ],
      ),
    );

void main() {
  testWidgets('shows title and explanation', (tester) async {
    await tester.pumpWidget(_wrap());
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();

    expect(find.text('Paiement en cours'), findsOneWidget);
    expect(find.textContaining('escrow'), findsOneWidget);
  });

  testWidgets('Fermer button closes the dialog', (tester) async {
    await tester.pumpWidget(_wrap());
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Fermer'));
    await tester.pumpAndSettle();

    expect(find.text('Paiement en cours'), findsNothing);
  });

  testWidgets('Voir mes envois button closes dialog and navigates', (tester) async {
    await tester.pumpWidget(_wrap());
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Voir mes envois'));
    await tester.pumpAndSettle();

    expect(find.text('Announcements'), findsOneWidget);
  });
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
flutter test test/features/settings/presentation/widgets/escrow_block_dialog_test.dart
```

Expected: FAIL — `EscrowBlockDialog` does not exist.

- [ ] **Step 3: Create the widget**

Create `yadony_app/lib/features/settings/presentation/widgets/escrow_block_dialog.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

class EscrowBlockDialog extends StatelessWidget {
  const EscrowBlockDialog({super.key});

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;

    return AlertDialog(
      title: Text(
        'Paiement en cours',
        style: tt.titleMedium?.copyWith(fontWeight: FontWeight.w700),
      ),
      content: Text(
        'Vous avez un paiement en cours (escrow). La suppression sera possible une fois la livraison confirmée.',
        style: tt.bodyMedium,
      ),
      actions: [
        TextButton(
          onPressed: () => context.pop(),
          child: const Text('Fermer'),
        ),
        FilledButton(
          onPressed: () {
            context.pop();
            // Navigate to announcements — closest to "active payments" until a
            // dedicated payments list screen is implemented.
            context.go('/announcements');
          },
          child: const Text('Voir mes envois'),
        ),
      ],
    );
  }
}
```

- [ ] **Step 4: Run to verify tests pass**

```bash
flutter test test/features/settings/presentation/widgets/escrow_block_dialog_test.dart
```

Expected: All 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add yadony_app/lib/features/settings/presentation/widgets/escrow_block_dialog.dart \
        yadony_app/test/features/settings/presentation/widgets/escrow_block_dialog_test.dart
git commit -m "feat(flutter): add EscrowBlockDialog widget"
```

---

## Task 7: DeleteAccountScreen

**Files:**
- Create: `yadony_app/lib/features/settings/presentation/delete_account_screen.dart`
- Create: `yadony_app/test/features/settings/presentation/delete_account_screen_test.dart`

- [ ] **Step 1: Write the failing test**

Create `yadony_app/test/features/settings/presentation/delete_account_screen_test.dart`:

```dart
import 'package:bloc_test/bloc_test.dart';
import 'package:yadony/core/di/injection.dart';
import 'package:yadony/features/auth/bloc/auth_bloc.dart';
import 'package:yadony/features/auth/bloc/auth_event.dart';
import 'package:yadony/features/auth/bloc/auth_state.dart';
import 'package:yadony/features/auth/data/models/user_model.dart';
import 'package:yadony/features/auth/data/repositories/auth_repository.dart';
import 'package:yadony/features/auth/data/services/local_auth_service.dart';
import 'package:yadony/features/settings/bloc/account_deletion_bloc.dart';
import 'package:yadony/features/settings/data/account_deletion_repository.dart';
import 'package:yadony/features/settings/presentation/delete_account_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mocktail/mocktail.dart';

class MockAccountDeletionBloc
    extends MockBloc<AccountDeletionEvent, AccountDeletionState>
    implements AccountDeletionBloc {}

class MockAuthBloc extends MockBloc<AuthEvent, AuthState>
    implements AuthBloc {}

class MockAccountDeletionRepository extends Mock
    implements AccountDeletionRepository {}

class MockAuthRepository extends Mock implements AuthRepository {}
class MockLocalAuthService extends Mock implements LocalAuthService {}

const _kSettle = Duration(milliseconds: 400);

void main() {
  late MockAccountDeletionBloc mockDeletionBloc;
  late MockAuthBloc mockAuthBloc;
  late MockAccountDeletionRepository mockRepo;

  setUpAll(() {
    registerFallbackValue(const RequestDeletion());
    registerFallbackValue(const ReactivateAccount());
    registerFallbackValue(const AuthCheckRequested());
  });

  setUp(() {
    mockDeletionBloc = MockAccountDeletionBloc();
    mockAuthBloc = MockAuthBloc();
    mockRepo = MockAccountDeletionRepository();

    if (getIt.isRegistered<AccountDeletionRepository>()) {
      getIt.unregister<AccountDeletionRepository>();
    }
    getIt.registerLazySingleton<AccountDeletionRepository>(() => mockRepo);

    whenListen<AccountDeletionState>(
      mockDeletionBloc,
      const Stream.empty(),
      initialState: const AccountDeletionInitial(),
    );
    whenListen<AuthState>(
      mockAuthBloc,
      const Stream.empty(),
      initialState: const AuthAuthenticated(UserModel(
        id: 'u1',
        roles: ['SENDER'],
        kycStatus: 'PENDING',
        status: 'ACTIVE',
      )),
    );
  });

  tearDown(() {
    if (getIt.isRegistered<AccountDeletionRepository>()) {
      getIt.unregister<AccountDeletionRepository>();
    }
  });

  Widget _wrap() => MaterialApp.router(
        routerConfig: GoRouter(
          routes: [
            GoRoute(
              path: '/',
              builder: (_, __) => MultiBlocProvider(
                providers: [
                  BlocProvider<AccountDeletionBloc>.value(
                      value: mockDeletionBloc),
                  BlocProvider<AuthBloc>.value(value: mockAuthBloc),
                ],
                child: const DeleteAccountScreen(),
              ),
            ),
            GoRoute(
              path: '/settings',
              builder: (_, __) => const Scaffold(body: Text('Settings')),
            ),
          ],
        ),
      );

  testWidgets('renders title and explanation text', (tester) async {
    await tester.pumpWidget(_wrap());
    await tester.pump(_kSettle);

    expect(find.text('Supprimer mon compte'), findsOneWidget);
    expect(find.textContaining('30 jours'), findsOneWidget);
  });

  testWidgets('Confirmer button dispatches RequestDeletion', (tester) async {
    await tester.pumpWidget(_wrap());
    await tester.pump(_kSettle);

    await tester.tap(find.text('Confirmer la suppression'));
    await tester.pump();

    verify(() => mockDeletionBloc.add(const RequestDeletion())).called(1);
  });

  testWidgets('shows EscrowBlockDialog when isEscrowBlocked is true',
      (tester) async {
    whenListen<AccountDeletionState>(
      mockDeletionBloc,
      Stream.fromIterable([
        const AccountDeletionLoading(),
        const AccountDeletionError(
          message: 'Paiement en cours',
          isEscrowBlocked: true,
        ),
      ]),
      initialState: const AccountDeletionInitial(),
    );

    await tester.pumpWidget(_wrap());
    await tester.pump(_kSettle);
    await tester.pumpAndSettle();

    expect(find.text('Paiement en cours'), findsOneWidget);
  });

  testWidgets('dispatches AuthCheckRequested and pops on AccountDeletionRequested',
      (tester) async {
    whenListen<AccountDeletionState>(
      mockDeletionBloc,
      Stream.fromIterable([
        const AccountDeletionLoading(),
        const AccountDeletionRequested(),
      ]),
      initialState: const AccountDeletionInitial(),
    );

    await tester.pumpWidget(_wrap());
    await tester.pump(_kSettle);
    await tester.pumpAndSettle();

    verify(() => mockAuthBloc.add(const AuthCheckRequested())).called(1);
  });
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
flutter test test/features/settings/presentation/delete_account_screen_test.dart
```

Expected: FAIL — `DeleteAccountScreen` does not exist.

- [ ] **Step 3: Create the screen**

Create `yadony_app/lib/features/settings/presentation/delete_account_screen.dart`:

```dart
import 'package:yadony/core/design/design_system.dart';
import 'package:yadony/features/auth/bloc/auth_bloc.dart';
import 'package:yadony/features/auth/bloc/auth_event.dart';
import 'package:yadony/features/settings/bloc/account_deletion_bloc.dart';
import 'package:yadony/features/settings/presentation/widgets/escrow_block_dialog.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

class DeleteAccountScreen extends StatelessWidget {
  const DeleteAccountScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;

    return BlocListener<AccountDeletionBloc, AccountDeletionState>(
      listener: (context, state) {
        if (state is AccountDeletionRequested) {
          context.read<AuthBloc>().add(const AuthCheckRequested());
          context.pop();
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: const Text(
                  'Votre compte sera supprimé dans 30 jours. Vous pouvez annuler depuis votre profil.'),
              backgroundColor: cs.error,
            ),
          );
        } else if (state is AccountDeletionError && state.isEscrowBlocked) {
          showDialog(
            context: context,
            builder: (_) => const EscrowBlockDialog(),
          );
        }
      },
      child: Scaffold(
        appBar: YadonyAppBar(title: 'Supprimer mon compte'),
        body: SingleChildScrollView(
          padding: const EdgeInsets.all(YadonySpacing.lg),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                padding: const EdgeInsets.all(YadonySpacing.base),
                decoration: BoxDecoration(
                  color: YadonyColors.errorLight,
                  borderRadius: BorderRadius.circular(YadonyRadius.card),
                  border: Border.all(
                      color: YadonyColors.error.withValues(alpha: 0.25)),
                ),
                child: Row(
                  children: [
                    const Icon(Icons.warning_amber_rounded,
                        color: YadonyColors.error, size: 20),
                    const SizedBox(width: YadonySpacing.md),
                    Expanded(
                      child: Text(
                        'Cette action entraîne la suppression de votre compte.',
                        style: tt.bodyMedium?.copyWith(
                          fontWeight: FontWeight.w600,
                          color: YadonyColors.error,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: YadonySpacing.xl),
              Text('Ce qui se passe',
                  style: tt.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
              const SizedBox(height: YadonySpacing.md),
              _InfoRow(
                icon: Icons.hourglass_empty_rounded,
                text:
                    'Période de grâce de 30 jours — vous pouvez revenir sur votre décision.',
              ),
              const SizedBox(height: YadonySpacing.md),
              _InfoRow(
                icon: Icons.archive_outlined,
                text:
                    'Vos annonces et bids actifs sont archivés immédiatement.',
              ),
              const SizedBox(height: YadonySpacing.md),
              _InfoRow(
                icon: Icons.delete_forever_outlined,
                text:
                    'Après 30 jours, vos données personnelles sont pseuyadonymisées (RGPD).',
              ),
              const SizedBox(height: YadonySpacing.xxl),
              BlocBuilder<AccountDeletionBloc, AccountDeletionState>(
                builder: (context, state) {
                  final isLoading = state is AccountDeletionLoading;
                  return YadonyButton(
                    label: 'Confirmer la suppression',
                    onPressed: isLoading
                        ? null
                        : () => context
                            .read<AccountDeletionBloc>()
                            .add(const RequestDeletion()),
                    variant: YadonyButtonVariant.danger,
                  );
                },
              ),
              if (BlocProvider.of<AccountDeletionBloc>(context).state
                  is AccountDeletionError) ...[
                const SizedBox(height: YadonySpacing.md),
                BlocBuilder<AccountDeletionBloc, AccountDeletionState>(
                  builder: (context, state) {
                    if (state is AccountDeletionError &&
                        !state.isEscrowBlocked) {
                      return Text(
                        state.message,
                        style: tt.bodySmall
                            ?.copyWith(color: YadonyColors.error),
                        textAlign: TextAlign.center,
                      );
                    }
                    return const SizedBox.shrink();
                  },
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  final IconData icon;
  final String text;
  const _InfoRow({required this.icon, required this.text});

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 18, color: cs.onSurfaceVariant),
        const SizedBox(width: YadonySpacing.md),
        Expanded(
          child: Text(text,
              style: tt.bodyMedium?.copyWith(color: cs.onSurfaceVariant)),
        ),
      ],
    );
  }
}
```

> **Note:** If `YadonyButton` does not have a `danger` variant, use `variant: YadonyButtonVariant.ghost` with a red `foregroundColor` override, or check `yadony_button.dart` for the available variants and use the closest one.

- [ ] **Step 4: Run to verify tests pass**

```bash
flutter test test/features/settings/presentation/delete_account_screen_test.dart
```

Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add yadony_app/lib/features/settings/presentation/delete_account_screen.dart \
        yadony_app/test/features/settings/presentation/delete_account_screen_test.dart
git commit -m "feat(flutter): add DeleteAccountScreen"
```

---

## Task 8: SettingsScreen

**Files:**
- Create: `yadony_app/lib/features/settings/presentation/settings_screen.dart`
- Create: `yadony_app/test/features/settings/presentation/settings_screen_test.dart`

- [ ] **Step 1: Write the failing test**

Create `yadony_app/test/features/settings/presentation/settings_screen_test.dart`:

```dart
import 'package:yadony/features/settings/presentation/settings_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

Widget _wrap() => MaterialApp.router(
      routerConfig: GoRouter(
        routes: [
          GoRoute(
            path: '/',
            builder: (_, __) => const SettingsScreen(),
          ),
          GoRoute(
            path: '/settings/delete-account',
            builder: (_, __) =>
                const Scaffold(body: Text('DeleteAccountScreen')),
          ),
        ],
      ),
    );

void main() {
  testWidgets('renders Paramètres title', (tester) async {
    await tester.pumpWidget(_wrap());
    await tester.pumpAndSettle();

    expect(find.text('Paramètres'), findsOneWidget);
  });

  testWidgets('shows Supprimer mon compte tile', (tester) async {
    await tester.pumpWidget(_wrap());
    await tester.pumpAndSettle();

    expect(find.text('Supprimer mon compte'), findsOneWidget);
  });

  testWidgets('tapping Supprimer mon compte navigates to delete-account screen',
      (tester) async {
    await tester.pumpWidget(_wrap());
    await tester.pumpAndSettle();

    await tester.tap(find.text('Supprimer mon compte'));
    await tester.pumpAndSettle();

    expect(find.text('DeleteAccountScreen'), findsOneWidget);
  });
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
flutter test test/features/settings/presentation/settings_screen_test.dart
```

Expected: FAIL — `SettingsScreen` does not exist.

- [ ] **Step 3: Create the screen**

Create `yadony_app/lib/features/settings/presentation/settings_screen.dart`:

```dart
import 'package:yadony/core/design/design_system.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;

    return Scaffold(
      appBar: YadonyAppBar(title: 'Paramètres'),
      body: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(
          horizontal: YadonySpacing.lg,
          vertical: YadonySpacing.xl,
        ),
        child: Column(
          children: [
            YadonyListSection(
              tiles: [
                YadonyListTile(
                  icon: Icons.notifications_outlined,
                  iconColor: YadonyColors.warning,
                  iconBgColor: YadonyColors.warningLight,
                  label: 'Notifications',
                  showDivider: false,
                  onTap: () {},
                ),
              ],
            ),
            const SizedBox(height: YadonySpacing.base),
            YadonyListSection(
              tiles: [
                YadonyListTile(
                  icon: Icons.delete_outline_rounded,
                  iconColor: YadonyColors.error,
                  iconBgColor: YadonyColors.errorLight,
                  label: 'Supprimer mon compte',
                  showDivider: false,
                  onTap: () => context.push('/settings/delete-account'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
```

- [ ] **Step 4: Run to verify tests pass**

```bash
flutter test test/features/settings/presentation/settings_screen_test.dart
```

Expected: All 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add yadony_app/lib/features/settings/presentation/settings_screen.dart \
        yadony_app/test/features/settings/presentation/settings_screen_test.dart
git commit -m "feat(flutter): add SettingsScreen with delete account entry"
```

---

## Task 9: Wire routes + update ProfileScreen

**Files:**
- Modify: `yadony_app/lib/app/router.dart`
- Modify: `yadony_app/lib/features/profile/presentation/profile_screen.dart`

- [ ] **Step 1: Add imports to router.dart**

Add at the top of `router.dart` alongside existing imports:

```dart
import 'package:yadony/features/settings/bloc/account_deletion_bloc.dart';
import 'package:yadony/features/settings/presentation/delete_account_screen.dart';
import 'package:yadony/features/settings/presentation/settings_screen.dart';
```

- [ ] **Step 2: Add /settings routes to router.dart**

Add these routes alongside the existing hors-shell routes (before the `StatefulShellRoute`):

```dart
// ── Settings (hors shell) ─────────────────────────────────────────────────
GoRoute(
  path: '/settings',
  builder: (context, state) => BlocProvider(
    create: (_) => getIt<AccountDeletionBloc>(),
    child: const SettingsScreen(),
  ),
  routes: [
    GoRoute(
      path: 'delete-account',
      builder: (context, state) => const DeleteAccountScreen(),
    ),
  ],
),
```

- [ ] **Step 3: Wrap /profile with AccountDeletionBloc**

In the `StatefulShellRoute` → Branch 4 (Profil), change:

```dart
// BEFORE:
GoRoute(
  path: '/profile',
  builder: (context, state) => BlocProvider(
    create: (_) => getIt<BidBloc>(),
    child: const ProfileScreen(),
  ),
),

// AFTER:
GoRoute(
  path: '/profile',
  builder: (context, state) => MultiBlocProvider(
    providers: [
      BlocProvider(create: (_) => getIt<BidBloc>()),
      BlocProvider(create: (_) => getIt<AccountDeletionBloc>()),
    ],
    child: const ProfileScreen(),
  ),
),
```

- [ ] **Step 4: Add imports to profile_screen.dart**

Add at the top alongside existing imports:

```dart
import 'package:yadony/features/auth/bloc/auth_event.dart';
import 'package:yadony/features/settings/bloc/account_deletion_bloc.dart';
import 'package:yadony/features/profile/presentation/widgets/pending_deletion_banner.dart';
```

- [ ] **Step 5: Add BlocListener for AccountDeletionBloc in profile_screen.dart**

Wrap the existing `BlocListener<AuthBloc, AuthState>` with a `MultiBlocListener`:

```dart
// BEFORE:
return BlocListener<AuthBloc, AuthState>(
  listener: (context, state) {
    if (state is AuthInitial || state is AuthAccountDeleted) {
      context.go('/auth/phone');
    }
  },
  child: ...
);

// AFTER:
return MultiBlocListener(
  listeners: [
    BlocListener<AuthBloc, AuthState>(
      listener: (context, state) {
        if (state is AuthInitial || state is AuthAccountDeleted) {
          context.go('/auth/phone');
        }
      },
    ),
    BlocListener<AccountDeletionBloc, AccountDeletionState>(
      listener: (context, state) {
        if (state is AccountReactivated) {
          context.read<AuthBloc>().add(const AuthCheckRequested());
        }
      },
    ),
  ],
  child: BlocBuilder<AuthBloc, AuthState>(
    // ... rest of the existing builder unchanged
  ),
);
```

- [ ] **Step 6: Add PendingDeletionBanner to profile body**

Inside the `BlocBuilder<AuthBloc, AuthState>` builder, add the banner after `_StatsRow` and before `_ProfileCompletionBanner`. The `user` variable is already available in scope:

```dart
// After _StatsRow(...) + SizedBox:
if (user != null && user.isPendingDeletion && user.deletionRequestedAt != null) ...[
  PendingDeletionBanner(
    deletionRequestedAt: user.deletionRequestedAt!,
    onReactivate: () => context
        .read<AccountDeletionBloc>()
        .add(const ReactivateAccount()),
  ).animate().fadeIn(delay: 160.ms),
  const SizedBox(height: YadonySpacing.lg),
],
```

- [ ] **Step 7: Add Settings tile to profile menu**

In the second `YadonyListSection` (the settings menu, the one with Notifications, Langue, etc.), add a Settings tile at the top:

```dart
YadonyListTile(
  icon: Icons.settings_outlined,
  iconColor: cs.onSurfaceVariant,
  iconBgColor: cs.outline.withValues(alpha: 0.3),
  label: 'Paramètres',
  onTap: () => context.push('/settings'),
),
```

- [ ] **Step 8: Run all Flutter tests**

```bash
cd /home/a-diakite/Desktop/MyProject/my_app/yadony_app
flutter test
```

Expected: All tests PASS (0 failures).

- [ ] **Step 9: Check for analysis errors**

```bash
flutter analyze
```

Expected: No issues found (or only pre-existing warnings, 0 new errors).

- [ ] **Step 10: Commit**

```bash
cd /home/a-diakite/Desktop/MyProject/my_app
git add yadony_app/lib/app/router.dart \
        yadony_app/lib/features/profile/presentation/profile_screen.dart
git commit -m "feat(flutter): wire settings routes and pending deletion banner in profile"
```

---

## Final verification

- [ ] **Run full test suite**

```bash
cd /home/a-diakite/Desktop/MyProject/my_app/yadony_app
flutter test --coverage
```

Expected: All tests pass.

- [ ] **Check coverage**

```bash
genhtml coverage/lcov.info -o coverage/html
# Open coverage/html/index.html — global coverage ≥ 90%
```

---

## Checklist before marking complete

- [ ] `flutter test` → 0 failures
- [ ] `flutter analyze` → 0 new errors
- [ ] Coverage ≥ 90% on new files
- [ ] `UserModel.isPendingDeletion` works correctly
- [ ] DELETE /auth/me → 422 shows `EscrowBlockDialog`
- [ ] DELETE /auth/me → 204 pops screen + refreshes profile
- [ ] Banner visible on profile when `status == PENDING_DELETION`
- [ ] "Annuler la suppression" → POST /auth/me/reactivate → banner disappears
- [ ] Settings tile visible in profile → navigates to SettingsScreen
- [ ] "Supprimer mon compte" in SettingsScreen → navigates to DeleteAccountScreen
