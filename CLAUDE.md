# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Project Overview

**dony** is a P2P marketplace mobile app connecting travelers (with luggage space) and senders from the African diaspora to transport packages to Africa (Paris/Lyon/Marseille → Dakar/Abidjan/Bamako/Douala).

This is a **monorepo** with three main components:
- **dony-back**: Spring Boot 3.4.x backend (Java 21)
- **dony_app**: Flutter mobile app (Dart, iOS 14+ / Android 8.0+)
- **docs-claude**: Comprehensive documentation, architecture, and user stories

**Tech Stack:**
- Backend: Spring Boot 3.4.x, PostgreSQL 16, Flyway migrations
- Frontend: Flutter (feature-first architecture)
- Auth: Firebase Authentication (Phone Auth)
- Payments: Stripe Connect Marketplace + Stripe Identity (KYC)
- Storage: Hetzner Object Storage (S3-compatible)
- Cache: Caffeine (in-memory, no Redis in MVP)
- Notifications: Firebase FCM + SMS fallback (Africa's Talking / Twilio)
- Infrastructure: Hetzner VPS CX31, Docker Compose, Nginx
- Monitoring: Sentry (Java + Flutter) + UptimeRobot

---

## Repository Structure

```
mon-dony/
├── dony-back/              # Spring Boot backend
├── dony_app/               # Flutter mobile app
└── docs-claude/
    ├── CLAUDE.md           # Detailed development rules (French)
    └── docs/
        ├── planning-artifacts/
        │   ├── architecture.md
        │   ├── epics.md
        │   └── prd.md
        └── stories/
            └── epic-*.md   # User stories organized by epic
```

---

## Common Development Commands

### Spring Boot Backend (dony-back/)

```bash
# Start in dev mode (PostgreSQL via Docker Compose)
./mvnw spring-boot:run -Dspring.profiles.active=dev

# Run tests
./mvnw test

# Build JAR
./mvnw package -DskipTests

# Start Docker containers (PostgreSQL)
docker compose -f docker-compose.dev.yml up -d

# Access PostgreSQL
docker exec -it dony_db psql -U dony -d dony_dev

# Check Flyway migrations
SELECT * FROM flyway_schema_history ORDER BY installed_rank;
```

### Flutter App (dony_app/)

```bash
# Run in dev mode
flutter run --dart-define-from-file=env.dev.json

# Analyze code
flutter analyze

# Run tests
flutter test

# Build production Android APK
flutter build apk --dart-define-from-file=env.prod.json

# Generate Hive files (after model changes)
flutter pub run build_runner build --delete-conflicting-outputs
```

**Note:** Environment files `env.dev.json` and `env.prod.json` must contain:
- `API_BASE_URL`
- `FIREBASE_PROJECT_ID`
- `STRIPE_PUBLISHABLE_KEY`
- `SENTRY_DSN`

---

## Architecture Overview

### Backend Structure (Package-per-Feature)

```
com.dony.api/
├── config/              # SecurityConfig, FirebaseConfig, CacheConfig
├── common/              # Shared utilities: BaseEntity, StorageService, GlobalExceptionHandler
├── auth/                # Firebase authentication, UserEntity, UserRepository
├── kyc/                 # Identity verification (Stripe Identity)
├── matching/            # Bid matching between travelers and senders
├── cancellation/        # DEDICATED cancellation feature (not in matching/)
├── tracking/            # QR code scanning and package tracking
├── payments/            # Stripe escrow, payouts (Wave/Orange Money for Africa)
├── notifications/       # FCM push + SMS fallback
├── disputes/            # Dispute resolution system
└── admin/               # Admin endpoints
```

**Critical Rules:**
- Each package = one business feature
- Cross-package communication via Spring Application Events (e.g., `DeliveryConfirmedEvent`)
- Never inject services directly across packages (use event-driven architecture)
- Shared logic goes in `common/`, never in generic `Utils.java`

### Frontend Structure (Feature-First)

```
lib/
├── app/
│   ├── app.dart
│   ├── router.dart          # GoRouter - all routes defined here
│   └── theme.dart
├── core/
│   ├── di/
│   │   └── injection.dart   # GetIt dependency injection
│   ├── network/
│   │   └── api_client.dart  # Single Dio instance
│   ├── storage/
│   │   └── hive_service.dart
│   └── error/
│       └── app_exception.dart
└── features/
    ├── auth/
    ├── kyc/
    ├── matching/
    ├── cancellation/        # DEDICATED feature
    ├── tracking/
    │   └── data/
    │       └── offline_queue.dart   # Hive - offline QR sync queue
    ├── payments/
    ├── notifications/
    ├── disputes/
    └── admin/
```

**Critical Rules:**
- Each feature MUST have exactly 3 subdirectories: `bloc/`, `data/`, `presentation/`
- State management: `flutter_bloc` only (no `setState`)
- Navigation: GoRouter only (no direct `Navigator.push()`)
- HTTP client: Dio only (no `http` package)

---

## Database Architecture

### Schemas
- **public**: All business tables (users, announcements, bids, tracking_events, payments, disputes, audit_log, cancellations)
- **kyc_schema**: KYC data only, with AES-256 encrypted columns

### Flyway Migrations (Sequential Order)

| Migration | Content |
|-----------|---------|
| V1 | `users`, `user_roles` |
| V2 | `kyc_schema`, `kyc_verifications` (encrypted columns) |
| V3 | `announcements`, `bids` |
| V4 | `tracking_events` |
| V5 | `payments` |
| V6 | `disputes` |
| V7 | `audit_log` + immutability trigger |
| V8 | `cancellations`, `rematch_suggestions` |

**Critical:** Never modify existing migrations - always create V(n+1) for changes.

### Audit Log (Immutable)

The `audit_log` table has a PostgreSQL trigger that prevents UPDATE and DELETE operations. Never attempt to modify audit entries.

---

## Key Technical Patterns

### 1. Authentication (Spring Boot)
- `FirebaseTokenFilter extends OncePerRequestFilter` validates Firebase ID token on every request
- 100% stateless (no server sessions)
- Public endpoints: `/api/v1/auth/**`, `/actuator/health`, `/tracking/{token}`, `/api/v1/kyc/webhook`, `/api/v1/ratings/recipient`

### 2. Authorization (RBAC)
- Roles: `ROLE_SENDER`, `ROLE_TRAVELER`, `ROLE_ADMIN`
- Users can have multiple roles (SENDER + TRAVELER)
- Use `@PreAuthorize("hasRole('ROLE')")` on controller methods
- All `/api/v1/admin/**` endpoints require `@PreAuthorize("hasRole('ADMIN')")`

### 3. Error Handling
- Always return RFC 7807 `ProblemDetail` from `GlobalExceptionHandler`
- Never return raw `String` or `Map` for errors
- Content-Type: `application/problem+json`

### 4. Entity Management
- All entities extend `BaseEntity` (UUID id, createdAt, updatedAt, deletedAt)
- Soft delete only: `@Where(clause = "deleted_at IS NULL")`
- Never perform physical DELETE on business entities

### 5. Event-Driven Architecture
- `tracking/` publishes `DeliveryConfirmedEvent` via `ApplicationEventPublisher`
- `payments/` listens with `@EventListener` in `DeliveryEventListener`
- Other events: `BidAcceptedEvent`, `TripCancelledEvent`, `DisputeOpenedEvent`

### 6. Offline QR Scanning (Flutter)
- Detect connectivity with `connectivity_plus`
- Store offline scans in Hive via `offline_queue.dart`
- Auto-sync on reconnection (must complete in <30s)
- Backend validates `offlineTimestamp` is not in the future (anti-fraud)

### 7. File Storage (Hetzner S3)
- Service: `StorageService.java` in `com.dony.api.common`
- QR photos: `tracking/{bidId}/{timestamp}_{eventType}.jpg`
- KYC docs: `kyc/{userId}/{timestamp}_{documentType}.jpg`
- KYC files: Always use presigned URLs with short expiration (never direct public URLs)

### 8. Stripe Payments
- `capture_method: manual` for all PaymentIntents (escrow mode)
- Commission: 12% via `application_fee_amount`
- Never capture without `DeliveryConfirmedEvent` (except admin force-release at J+48)
- Always validate webhook signatures before processing

---

## Critical Development Rules

### Never Violate These Rules:

1. **No secrets in code or git** - Use environment variables
2. **No physical deletes** - Soft delete only for business entities
3. **No migration modifications** - Create new migration V(n+1)
4. **No audit_log modifications** - Immutable by design (trigger prevents it)
5. **No direct cross-package service calls** - Use Spring Application Events
6. **Cancellation logic in cancellation/ package** - Not in matching/
7. **No Redis in MVP** - Caffeine cache only
8. **No PaymentIntent capture without DeliveryConfirmedEvent** - Except admin force-release
9. **No direct KYC file URLs** - Presigned URLs only
10. **No setState in Flutter** - BLoC pattern only
11. **No raw exceptions from controllers** - GlobalExceptionHandler only
12. **No Navigator.push() in Flutter** - GoRouter only

### Security Rules:

- Always validate server-side, even if validated client-side
- Max declared value: 500€ (reject with HTTP 422)
- Biometric/PIN for payments (Flutter-side before API call)
- Rate limiting configured in Nginx (not Spring Boot):
  - General API: 30 req/min per IP
  - `/auth` and `/kyc`: 5 req/min per IP
- Always verify resource ownership before modification (prevent unauthorized access)

---

## Implementation Story Order

**Must follow strict dependency order:**

1. **Epic 1** (Setup) - Complete before all others
2. **Epic 2** (Auth/KYC) - Complete before Epics 3, 4, 5
3. **Story 6.1** (Stripe Spike) - Complete in isolation BEFORE other Epic 6 stories
4. **Epic 7** (QR Tracking) - Depends on Epic 6 (payment creates escrow before QR scan)

### Story Implementation Checklist:

Before starting:
- [ ] Read full story in `docs-claude/docs/stories/epic-XX-*.md`
- [ ] Verify dependencies (FRs, ARCHs, epic dependencies)
- [ ] Identify JPA entities involved
- [ ] Identify Spring events to publish/listen
- [ ] Identify audit_log entries needed

Before marking complete:
- [ ] All Given/When/Then acceptance criteria covered
- [ ] Audit_log entries created for significant actions
- [ ] Errors return RFC 7807 ProblemDetail
- [ ] Soft delete implemented (no physical DELETE)
- [ ] Sensitive data in kyc_schema or encrypted
- [ ] Admin endpoints protected with `@PreAuthorize("hasRole('ADMIN')")`
- [ ] Flutter feature uses BLoC + GoRouter (no setState or direct Navigator)

---

## Documentation Reference

**Primary source:** `docs-claude/CLAUDE.md` contains exhaustive French-language development rules.

**Additional documentation:**
- Architecture: `docs-claude/docs/planning-artifacts/architecture.md`
- Product Requirements: `docs-claude/docs/planning-artifacts/prd.md`
- Epic Index: `docs-claude/docs/planning-artifacts/epics.md`
- User Stories: `docs-claude/docs/stories/epic-01-*.md` through `epic-10-*.md`

**Note:** The detailed CLAUDE.md in docs-claude/ is authoritative for all implementation decisions. This root-level CLAUDE.md provides quick reference for common tasks.
