# Story Referral — Parrainage 5€ après première livraison (Backend)

**Date :** 2026-05-12
**Status :** ✅ Complète
**Migration :** V69

## Résumé

Système de parrainage complet : génération automatique de codes uniques à l'inscription, échange de code au signup, récompense de 5€ au referrer lors de la première livraison du filleul. Toute la logique de récompense passe par `DeliveryConfirmedEvent` existant.

## Fichiers créés

**Migration**
- `db/migration/V69__create_referral.sql` — tables `referral_codes`, `referral_invitations`, `user_credits`

**Événement**
- `auth/events/UserRegisteredEvent.java` — record `(UUID userId, String firebaseUid)` publié dans `AuthService.createUser()`

**Package `com.dony.api.referral`**
- `ReferralCodeEntity.java` — table `referral_codes`, pas de BaseEntity (pas de soft delete sur les codes)
- `ReferralCodeRepository.java` — `findByUserId`, `findByCode`, `existsByCode`
- `ReferralInvitationEntity.java` — étend `BaseEntity`, `@Where deleted_at IS NULL`, cycle `PENDING → SIGNED_UP → REWARDED`
- `ReferralInvitationRepository.java` — `findByRefereeUserIdAndStatus`, `countByReferrerUserId`, `countByReferrerUserIdAndStatus`
- `UserCreditEntity.java` — ledger immuable, pas de BaseEntity
- `UserCreditRepository.java`
- `ReferralConfig.java` — `@ConfigurationProperties(prefix="dony.referral")` : `rewardAmountCents`, `maxInvitationsPerUser`, `codeRegenerationCooldownDays`
- `ReferralService.java` — génération code, lazy creation, redeem avec vérifications, regenerate avec cooldown
- `UserRegisteredReferralListener.java` — `@EventListener` simple, erreur non bloquante
- `DeliveryConfirmedReferralListener.java` — `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`
- `ReferralController.java` — 3 endpoints
- `MyReferralResponse.java`, `RedeemCodeRequest.java`

**Tests**
- `referral/ReferralServiceTest.java` — 7 tests unitaires
- `referral/DeliveryConfirmedReferralListenerTest.java` — 4 tests unitaires
- `referral/ReferralControllerIntegrationTest.java` — 6 tests MockMvc

## Fichiers modifiés

- `auth/AuthService.java` — injection `ApplicationEventPublisher`, publication `UserRegisteredEvent` après save
- `matching/BidRepository.java` — ajout `countByStatusAndSenderId` via `@Query`
- `resources/application.yml` — bloc `dony.referral`
- `test/resources/application-test.yml` — bloc `dony.referral`
- `auth/AuthServiceTest.java` — mock `ApplicationEventPublisher` ajouté

## Comment ça fonctionne

### Génération du code (inscription)

```
UserRegisteredEvent → UserRegisteredReferralListener.onUserRegistered()
  → ReferralService.generateCodeForUser(userId)
    → prefix = 4 premières lettres du displayName en UPPER (ex: "ABOU")
    → code = prefix + 4 chiffres aléatoires (ex: "ABOU2847")
    → retry max 5 fois si existsByCode = true
    → save ReferralCodeEntity
```

Le listener avale les exceptions pour ne jamais bloquer l'inscription.

### Échange de code (signup)

```
POST /referral/redeem { code: "ABOU2847" }
  → ReferralService.redeemCode(firebaseUid, code)
    → vérifie code valide → trouve referrer
    → vérifie referee != referrer (409 self-referral)
    → vérifie pas déjà parrainé (409 already-referred)
    → crée ReferralInvitationEntity(status=SIGNED_UP)
    → audit log
```

### Récompense (1ère livraison)

```
DeliveryConfirmedEvent (AFTER_COMMIT, senderId=X)
  → DeliveryConfirmedReferralListener
    → cherche invitation SIGNED_UP pour senderId (en tant que referee)
    → si absente → return
    → countByStatusAndSenderId("COMPLETED", senderId) == 1 → continue
    → invitation.status = REWARDED + rewardedAt
    → UserCreditEntity(userId=referrerId, amountCents=500, source="REFERRAL_REWARD")
    → audit log
```

### Points d'entrée API

- `GET /me/referral` — `hasAnyRole('SENDER', 'TRAVELER')` → code + shareUrl + stats
- `POST /me/referral/regenerate` — `hasAnyRole('SENDER', 'TRAVELER')` → cooldown configurable
- `POST /referral/redeem` body `{code}` — `hasAnyRole('SENDER', 'TRAVELER')` → 200 OK ou 409

### Configuration externalisée

```yaml
dony:
  referral:
    reward-amount-cents: 500        # 5€
    max-invitations-per-user: 50
    code-regeneration-cooldown-days: 30
```

## Pièges et points d'attention

- **`@TransactionalEventListener(AFTER_COMMIT)` obligatoire** sur `DeliveryConfirmedReferralListener` : si on utilisait `@EventListener` simple, le listener pourrait lire un bid dont le statut n'est pas encore commité. Pattern AFTER_COMMIT + REQUIRES_NEW garantit une transaction propre.
- **countByStatusAndSenderId == 1** : on vérifie que c'est exactement la première livraison du filleul. Si == 0 (incohérence), si > 1 (pas la première) → pas de récompense.
- **UserCreditEntity immuable** : pas de soft delete, pas d'update. C'est un ledger append-only. Pour annuler un crédit, il faut créer une ligne négative.

## Critères d'acceptation couverts

- [x] Code unique généré automatiquement à l'inscription
- [x] Auto-parrainage interdit (409)
- [x] Double parrainage interdit (409)
- [x] Récompense uniquement sur la 1re livraison du filleul
- [x] Cooldown sur la régénération de code
- [x] Config externalisée dans `application.yml`
- [x] Audit log sur échange + récompense
- [x] `@TransactionalEventListener(AFTER_COMMIT)` sur le listener

## Tests

- `./mvnw test` → 694 tests (0 rouge)
- Couverture ≥ 90% sur le nouveau code
