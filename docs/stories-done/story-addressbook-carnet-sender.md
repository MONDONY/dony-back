# Story Addressbook — Carnet sender : adresses pickup, destinataires, voyageurs favoris (Backend)

**Date :** 2026-05-12
**Status :** ✅ Complète
**Migration :** V68

## Résumé

Ajout du carnet d'adresses complet côté expéditeur : CRUD adresses de pickup avec gestion du défaut, CRUD destinataires avec validation E.164 et restriction pays, et lecture/suppression des voyageurs favoris. Tous les endpoints sont protégés `ROLE_SENDER`.

## Fichiers créés

- `addressbook/pickup/PickupAddressEntity.java` — entité, table `pickup_addresses`, `@Where deleted_at IS NULL`
- `addressbook/pickup/PickupAddressRepository.java` — `findByUserIdOrderByIsDefaultDescUpdatedAtDesc`, `findDefaultByUserId`
- `addressbook/pickup/PickupAddressService.java` — CRUD + `setDefault` (transaction : unset all → set one) + audit log
- `addressbook/pickup/PickupAddressController.java` — 5 endpoints : GET list, POST 201, PUT, PATCH `/set-default`, DELETE 204
- `addressbook/pickup/dto/PickupAddressDto.java` — DTO lecture
- `addressbook/pickup/dto/CreatePickupAddressRequest.java` — Bean Validation (`@NotBlank`, `@Size`)
- `addressbook/pickup/dto/UpdatePickupAddressRequest.java`
- `addressbook/recipient/RecipientEntity.java` — table `recipients`, validation phone E.164
- `addressbook/recipient/RecipientRepository.java`
- `addressbook/recipient/RecipientService.java`
- `addressbook/recipient/RecipientController.java` — bean qualifier `"addressbookRecipientController"` (évite conflit avec `tracking.RecipientController`)
- `addressbook/recipient/dto/CreateRecipientRequest.java` — `@Pattern(regexp="^\+[1-9]\d{1,14}$")`, country enum SN/CI/ML/CM
- `addressbook/recipient/dto/RecipientDto.java`
- `addressbook/recipient/dto/UpdateRecipientRequest.java`
- `addressbook/favorite/FavoriteTravelerEntity.java` — table `favorite_travelers`
- `addressbook/favorite/FavoriteTravelerRepository.java`
- `addressbook/favorite/FavoriteTravelerService.java` — lecture + suppression, enrichit le DTO avec `displayName` + `averageRating` via `UserRepository`
- `addressbook/favorite/FavoriteTravelerController.java`
- `addressbook/favorite/dto/FavoriteTravelerDto.java`
- `addressbook/favorite/dto/AddFavoriteTravelerRequest.java`
- `src/main/resources/db/migration/V68__create_addressbook.sql`

## Fichiers de test créés

- `addressbook/pickup/PickupAddressServiceTest.java` — 8 tests unitaires
- `addressbook/pickup/PickupAddressControllerIntegrationTest.java` — 9 tests MockMvc
- `addressbook/recipient/RecipientServiceTest.java` — 6 tests unitaires
- `addressbook/recipient/RecipientControllerIntegrationTest.java` — 9 tests MockMvc
- `addressbook/favorite/FavoriteTravelerServiceTest.java` — 7 tests unitaires
- `addressbook/favorite/FavoriteTravelerControllerIntegrationTest.java` — 9 tests MockMvc

## Comment ça fonctionne

### Flux utilisateur (adresses pickup)

1. `GET /pickup-addresses` → retourne la liste triée (défaut en premier, puis par date)
2. `POST /pickup-addresses` → crée, si `isDefault=true` appelle `setDefault` qui unset les autres dans la même transaction
3. `PATCH /pickup-addresses/{id}/set-default` → dédié au changement de défaut sans modifier les autres champs
4. `DELETE /pickup-addresses/{id}` → soft delete uniquement (`deletedAt = now()`)

### Règle `setDefault`

```java
@Transactional
public void setDefault(UUID userId, UUID addressId) {
    // 1. Unset toutes les adresses du user
    pickupAddressRepository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId)
        .forEach(a -> { a.setIsDefault(false); pickupAddressRepository.save(a); });
    // 2. Set la nouvelle
    PickupAddressEntity target = pickupAddressRepository.findByUserIdAndId(userId, addressId)
        .orElseThrow(...);
    target.setIsDefault(true);
    pickupAddressRepository.save(target);
}
```

**Pourquoi pas un partial update direct ?** Pour garantir l'invariant "un seul défaut par user" en cas de concurrence sans optimistic locking sur cette table.

### Validation phone (destinataires)

`@Pattern(regexp = "^\\+[1-9]\\d{1,14}$")` — format E.164 strict. Le pays est une enum restreinte à `SN`, `CI`, `ML`, `CM` pour forcer le périmètre géographique dony.

### Conflit de beans `RecipientController`

`tracking/RecipientController` et `addressbook/recipient/RecipientController` ont le même nom simple. Solution : `@RestController("addressbookRecipientController")` sur le second. Spring utilise le qualifier pour différencier.

### Favoris (voyageurs)

Lecture seule + suppression. Le service enrichit le DTO en récupérant `displayName` et `averageRating` depuis `UserRepository`. Le `FavoriteTravelerEntity` stocke l'`userId` du sender et le `travelerId`.

## Critères d'acceptation couverts

- [x] CRUD adresses pickup avec set-default atomique
- [x] Soft delete uniquement (`deleted_at`)
- [x] Validation E.164 sur le phone des destinataires
- [x] Restriction pays SN/CI/ML/CM
- [x] Favoris : lecture enrichie + suppression
- [x] `@PreAuthorize("hasRole('SENDER')")` sur tous les endpoints
- [x] Audit log sur create/update/delete

## Tests

- `./mvnw test` → 662 → 694 tests (0 rouge)
- Couverture ≥ 90% sur le nouveau code

## Décisions techniques

- **Tri adresses** : `findByUserIdOrderByIsDefaultDescUpdatedAtDesc` — le défaut toujours en tête, puis les plus récemment modifiées. Alternative écartée : tri côté client (non fiable).
- **Favoris read-only** : les favoris sont créés côté voyageur (lors d'un bid COMPLETED). Ce controller n'expose que la lecture et la suppression pour l'expéditeur.
- **Bean qualifier** : évite de renommer `tracking.RecipientController` qui est déjà stabilisé et testé.
