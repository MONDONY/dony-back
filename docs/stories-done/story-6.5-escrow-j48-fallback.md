# Story 6.5 — Déblocage opérateur à J+48 (fallback) (Backend)

**Date:** 2026-04-26
**Status:** ✅ Complète

## Résumé
Un scheduler toutes les heures détecte les paiements ESCROW depuis plus de 48h et crée des alertes dans `admin_alerts`. Un endpoint admin permet la libération forcée manuelle avec audit trail.

## Fichiers créés
- `db/migration/V20__admin_alerts.sql` — table `admin_alerts` (id, type, payload JSONB, resolved, created_at)
- `admin/AdminAlertEntity.java` — entity JPA sans BaseEntity (pas de updated_at/deleted_at)
- `admin/AdminAlertRepository.java` — `findByTypeAndResolved(String, boolean)`
- `payments/EscrowScheduler.java` — `@Scheduled(cron = "0 0 * * * *")`, idempotent
- `admin/AdminPaymentController.java` — `POST /admin/payments/{id}/force-release`

## Fichiers modifiés
- `payments/PaymentRepository.java` — ajout `findByStatusAndCreatedAtBefore(PaymentStatus, LocalDateTime)`

## Comment ça fonctionne

### Scheduler (toutes les heures)
1. Requête : payments avec `status=ESCROW` et `createdAt < now - 48h`
2. Charge tous les alerts `ESCROW_J48_TIMEOUT` non-résolus en une seule requête (anti N+1)
3. Pour chaque payment : vérifie si une alerte non-résolue existe déjà (payload.contains(paymentId))
4. Si non : crée `AdminAlertEntity{type="ESCROW_J48_TIMEOUT", payload=JSON{paymentId,bidId,amount}}`
5. Log WARN par alerte créée

### Force-release admin
1. `POST /api/v1/admin/payments/{id}/force-release` — `@PreAuthorize("hasRole('ADMIN')")`
2. Valide `payment.status == ESCROW` (422 sinon)
3. `PaymentIntent.retrieve()` + `pi.capture()` — StripeException → 500
4. `status=RELEASED`, `escrowReleasedAt=now()`
5. Résout les alertes `ESCROW_J48_TIMEOUT` liées à ce payment
6. Audit_log : `ESCROW_FORCE_RELEASED`

### Points d'entrée API
- `POST /api/v1/admin/payments/{id}/force-release` — ROLE_ADMIN requis

### Entités JPA impliquées
- `AdminAlertEntity` → table `admin_alerts` — ne s'étend pas BaseEntity, pas de soft-delete (les alertes se résolvent via `resolved=true`)

### Pièges et points d'attention
- Le scheduler est idempotent : il ne crée PAS de doublon d'alerte grâce à la vérification payload.contains(paymentId)
- `AdminAlertEntity` n'a PAS de `@Where(deleted_at IS NULL)` — les alertes ne sont jamais supprimées
- La table `admin_alerts` n'a pas de FK vers `payments` pour éviter les contraintes croisées entre packages
- Le payload est un JSON string builddé manuellement (pas de Jackson) pour garder la dépendance minimale

## Critères d'acceptation couverts
- [x] Given paiement ESCROW depuis 48h → When scheduler s'exécute → Then alerte `ESCROW_J48_TIMEOUT` créée
- [x] Given alerte déjà existante → When scheduler s'exécute → Then pas de doublon
- [x] Given opérateur déclenche force-release → Then capture Stripe + audit_log `ESCROW_FORCE_RELEASED`

## Décisions techniques
- **Pas de FK admin_alerts → payments** : évite le couplage entre packages au niveau base de données
- **JSON string pour payload** vs Jackson : simplifie les dépendances dans le package admin
- **Résolution des alertes dans le controller** plutôt qu'un event : l'opération admin est synchrone et atomique
