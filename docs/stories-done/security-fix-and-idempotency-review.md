# Branche `security/fix-and-idempotency-review` — Résumé des changements

**Date :** 2026-05-06
**Branche :** `security/fix-and-idempotency-review`
**Base :** `main`
**Status :** Non committé — en attente de review et tests

---

## Vue d'ensemble

Cette branche corrige plusieurs failles de sécurité et problèmes d'idempotence identifiés sur le backend. Aucun nouveau endpoint n'est ajouté — uniquement des corrections sur du code existant et de nouvelles contraintes DB.

---

## 1. Sécurité — `FirebaseTokenFilter`

**Fichier :** `src/main/java/com/dony/api/auth/FirebaseTokenFilter.java`

**Problème :** En cas d'indisponibilité temporaire de la base de données, le filtre accordait quand même l'accès avec un token Firebase valide mais sans rôles (token "bare UID").

**Fix :** En cas d'échec DB, on vide le `SecurityContext` et on répond `503 Service Unavailable` au lieu d'accorder l'accès.

```java
// AVANT (dangereux)
catch (Exception e) {
    setAuthentication(uid, List.of()); // accès accordé sans rôles !
}

// APRÈS (correct)
catch (Exception e) {
    SecurityContextHolder.clearContext();
    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service temporarily unavailable");
    return true;
}
```

---

## 2. Idempotence webhooks Stripe (paiements + KYC)

**Fichiers :**
- `src/main/java/com/dony/api/common/ProcessedStripeEvent.java` *(nouveau)*
- `src/main/java/com/dony/api/common/ProcessedStripeEventRepository.java` *(nouveau)*
- `src/main/java/com/dony/api/payments/PaymentService.java`
- `src/main/java/com/dony/api/kyc/KycService.java`
- `src/main/resources/db/migration/V48__idempotency_constraints.sql`

**Problème :** Stripe rejoue les webhooks en cas de timeout. Un même event reçu deux fois pouvait déclencher deux captures de paiement ou deux validations KYC.

**Fix :** Avant chaque traitement, on vérifie si l'event ID Stripe existe déjà dans `processed_stripe_events`. Si oui → skip immédiat.

```java
if (processedStripeEventRepository.existsByEventId(event.getId())) {
    log.info("Stripe event {} already processed — skipping", event.getId());
    return;
}
processedStripeEventRepository.save(new ProcessedStripeEvent(event.getId()));
```

---

## 3. Anti-double-capture atomique

**Fichiers :**
- `src/main/java/com/dony/api/payments/PaymentEntity.java`
- `src/main/java/com/dony/api/payments/PaymentRepository.java`
- `src/main/java/com/dony/api/payments/BidAcceptedEventListener.java`

**Problème :** Race condition possible entre deux threads tentant de capturer le même `PaymentIntent` Stripe simultanément.

**Fix :**
- Colonne `captured_at` ajoutée sur `PaymentEntity`
- Méthode `markCapturedIfEscrow()` : UPDATE atomique avec condition `WHERE captured_at IS NULL AND status = ESCROW`. Retourne `0` si déjà capturé → skip immédiat

```java
// Dans BidAcceptedEventListener.capturePayment()
int updated = paymentRepository.markCapturedIfEscrow(payment.getId(), Instant.now());
if (updated == 0) {
    log.info("Payment {} already captured or not in ESCROW — skipping", payment.getId());
    return;
}
PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
pi.capture();
```

---

## 4. Event listeners : `@EventListener` → `@TransactionalEventListener`

**Fichiers :**
- `src/main/java/com/dony/api/payments/BidAcceptedEventListener.java`
- `src/main/java/com/dony/api/payments/DeliveryEventListener.java`
- `src/main/java/com/dony/api/payments/TripCancelledEventListener.java`

**Problème :** Les listeners s'exécutaient **pendant** la transaction parente. Ils lisaient donc des données pas encore commitées en base (ex. : un bid accepté mais dont le statut n'était pas encore visible en DB).

**Fix :** Remplacement de `@EventListener` + `@Transactional` par `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(propagation = REQUIRES_NEW)`. Les listeners démarrent une **nouvelle** transaction après le commit de la transaction parente.

---

## 5. Race condition Stripe Connect (`createConnectAccount`)

**Fichiers :**
- `src/main/java/com/dony/api/auth/UserRepository.java`
- `src/main/java/com/dony/api/payments/PaymentService.java`
- `src/main/resources/db/migration/V48__idempotency_constraints.sql`

**Problème :** Deux appels simultanés à `createConnectAccount` pouvaient créer deux comptes Stripe Connect pour le même utilisateur.

**Fix :**
- `findByIdForUpdate()` dans `UserRepository` avec `@Lock(PESSIMISTIC_WRITE)`
- Contrainte `UNIQUE` sur `users.stripe_account_id` en DB
- Dans `PaymentService.createConnectAccount()` : on acquiert le lock, puis on re-vérifie l'existence du compte avant de créer

```java
// Lock d'abord, re-check ensuite
user = userRepository.findByIdForUpdate(user.getId()).orElseThrow(...);
if (user.getStripeAccountId() != null) {
    return new ConnectAccountResponse(...); // déjà créé par un autre thread
}
```

---

## 6. Ownership check sur `GET /payments/bid/{bidId}`

**Fichiers :**
- `src/main/java/com/dony/api/payments/PaymentController.java`
- `src/main/java/com/dony/api/payments/PaymentService.java`

**Problème :** N'importe quel utilisateur avec le rôle `SENDER` ou `TRAVELER` pouvait lire le paiement de n'importe quel bid, même s'il n'en faisait pas partie.

**Fix :** `getPaymentStatusForBid()` vérifie que le caller est bien l'expéditeur du bid OU le voyageur de l'annonce. Sinon → `403 Forbidden`.

---

## 7. Fuite du numéro de téléphone dans `TravelerProfileDto`

**Fichiers :**
- `src/main/java/com/dony/api/matching/dto/TravelerProfileDto.java`
- `src/main/java/com/dony/api/matching/AnnouncementService.java` (3 occurrences)

**Problème :** Le DTO `TravelerProfileDto` exposait le numéro de téléphone du voyageur dans les réponses de matching (accessible à n'importe quel expéditeur).

**Fix :** Champ `phoneNumber` supprimé du DTO et de tous les appels dans `AnnouncementService`.

---

## 8. Spoofing d'IP via `X-Forwarded-For`

**Fichier :** `src/main/java/com/dony/api/matching/BidService.java`

**Problème :** Le code prenait le **premier** élément du header `X-Forwarded-For`, que le client peut forger librement. Le vrai IP client est le **dernier** élément, ajouté par le proxy de confiance (Nginx).

**Fix :**
```java
// AVANT (spoofable)
return forwarded.split(",")[0].trim();

// APRÈS (correct)
String[] parts = forwarded.split(",");
return parts[parts.length - 1].trim(); // ajouté par le proxy de confiance
```

---

## 9. KYC — activation des vérifications + idempotence session

**Fichiers :**
- `src/main/java/com/dony/api/kyc/KycService.java`
- `src/main/java/com/dony/api/matching/AnnouncementService.java`
- `src/main/java/com/dony/api/matching/BidService.java`
- `src/main/resources/application-dev.yml`
- Migrations V46, V47

**Problèmes :**
- Les vérifications KYC avant création de bid/annonce étaient commentées avec un TODO
- Un double-clic sur "Commencer KYC" créait deux sessions Stripe Identity
- Le statut KYC par défaut était `PENDING` pour les nouveaux utilisateurs (mauvaise sémantique)
- Le statut `REQUIRES_INPUT` (Stripe) n'était pas aligné avec `REJECTED` (enum Java)

**Fix :**
- KYC enforce activé via flag `dony.kyc.enforce` — `false` en dev, `true` en prod
- Si l'utilisateur est déjà `PENDING` avec une session existante, on retourne cette session sans en créer une nouvelle
- `NOT_STARTED` ajouté comme statut initial correct (migration V47)
- `REQUIRES_INPUT` → `REJECTED` en DB (migration V46), colonnes mortes supprimées

---

## 10. Optimistic locking sur `UserEntity`

**Fichiers :**
- `src/main/java/com/dony/api/auth/UserEntity.java`
- `src/main/resources/db/migration/V49__add_user_version.sql`

**Fix :** Champ `@Version` ajouté sur `UserEntity`. Protège contre les updates concurrents silencieux sur la même ligne user (ex. : double mise à jour du statut KYC).

---

## Migrations Flyway créées

| Migration | Contenu |
|---|---|
| `V46__kyc_cleanup.sql` | Aligne `REQUIRES_INPUT` → `REJECTED`, supprime colonnes mortes `id_document_encrypted` et `selfie_url` |
| `V47__add_kyc_status_not_started.sql` | Défaut `NOT_STARTED` sur `users.kyc_status`, reclassifie les PENDING sans session KYC |
| `V48__idempotency_constraints.sql` | UNIQUE sur `stripe_verification_session_id`, `stripe_account_id`, index partiel `uq_kyc_user_id_active`, index `uq_tracking_one_depart_per_bid`, colonne `payments.captured_at`, table `processed_stripe_events` |
| `V49__add_user_version.sql` | Colonne `users.version` pour optimistic locking |

---

## Tests

```bash
cd dony-back/

# Tous les tests doivent passer
./mvnw test

# Rapport de couverture JaCoCo
./mvnw test jacoco:report
# Ouvrir : target/site/jacoco/index.html — couverture ≥ 90 %
```

**Scénarios à tester manuellement :**

| Scénario | Résultat attendu |
|---|---|
| Créer un bid sans KYC (profil dev) | Autorisé (`dony.kyc.enforce=false` en dev) |
| Créer un bid sans KYC (profil prod) | `403 Forbidden` avec `kyc-not-verified` |
| Rejouer un webhook Stripe (même event ID) | Log `already processed — skipping`, aucun effet |
| `GET /api/v1/payments/bid/{bidId}` avec un token non concerné | `403 Forbidden` |
| Double appel `POST /api/v1/payments/connect-account` concurrent | Un seul compte Stripe créé |
| Démarrer KYC deux fois de suite (statut PENDING) | La même session Stripe est retournée |
