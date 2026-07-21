# Filtre `matchingMyTrips` sur la recherche de demandes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ajouter un paramètre `matchingMyTrips` à `GET /package-requests` qui restreint les demandes à celles compatibles avec les trajets actifs du voyageur connecté, triées par score de compatibilité, combinable avec tous les filtres existants.

**Architecture:** La règle de match (corridor + poids + fenêtre de date) est déjà implémentée en mémoire Java dans `MatchingService.findMatchingRequests`. On la réutilise comme source de vérité unique plutôt que de la dupliquer en SQL : le service produit les ids compatibles avec leur score, la recherche paginée existante est restreinte par `id IN (:ids)`, puis triée et paginée en Java.

**Tech Stack:** Spring Boot 3.4, Java 21, JPA Specifications, JUnit 5, Mockito, MockMvc.

**Spec :** `../../../dony_app/docs/superpowers/specs/2026-07-22-recherche-modes-colis-trajets-design.md` (§ 3)

## Global Constraints

- Branche : `feature/matching-my-trips-filter`. Ne jamais commit sur `main`.
- Jamais de ligne `Co-Authored-By: Claude` dans les messages de commit.
- Aucune migration Flyway : ce plan ne touche pas au schéma.
- `matchingMyTrips=false` n'est jamais envoyé explicitement par le client — même convention que `urgent`. Traiter uniquement `Boolean.TRUE.equals(...)`.
- Erreurs via `GlobalExceptionHandler` uniquement, jamais de `String` ou `Map` brut.
- `./mvnw test` doit passer à 0 rouge avant chaque commit. Couverture ≥ 90 %.
- Le comportement sans `matchingMyTrips` doit rester **strictement identique** : chaque tâche ajoute un test de non-régression.

---

### Task 1: Déduplication des matchs par demande

`MatchingService.findMatchingRequests` produit un DTO par couple (trajet, demande). Une demande compatible avec deux trajets du même voyageur apparaît deux fois. C'est sans conséquence sur l'écran actuel qui liste des couples, mais ça produirait des doublons dans une page de résultats. On expose une méthode dédiée qui déduplique par demande en gardant le meilleur score.

**Files:**
- Modify: `src/main/java/com/dony/api/matching/MatchingService.java:35-59`
- Test: `src/test/java/com/dony/api/matching/MatchingServiceTest.java`

**Interfaces:**
- Consumes: `MatchingService.findMatchingRequests(UUID)` existant, inchangé.
- Produces: `MatchingService.findBestMatchByRequestId(UUID travelerId)` retournant `Map<UUID, MatchInfo>` et le record public `MatchingService.MatchInfo(UUID requestId, UUID tripId, LocalDate tripDepartureDate, int matchScore)`. La `Map` est ordonnée par score décroissant (`LinkedHashMap`), ce qui donne l'ordre de tri aux tâches suivantes.

- [ ] **Step 1: Write the failing test**

Ajouter à `src/test/java/com/dony/api/matching/MatchingServiceTest.java`. Reprendre les helpers de construction d'entités déjà présents dans ce fichier (`announcement(...)`, `packageRequest(...)` ou équivalents) ; s'ils portent d'autres noms, adapter les appels sans changer les assertions.

```java
    @Test
    void findBestMatchByRequestId_dedupliqueUneDemandeCompatibleAvecDeuxTrajets() {
        UUID travelerId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        // Deux trajets du même voyageur sur le même corridor, à des dates proches.
        AnnouncementEntity trajetFaible = new AnnouncementEntity();
        trajetFaible.setId(UUID.randomUUID());
        trajetFaible.setTravelerId(travelerId);
        trajetFaible.setDepartureCity("Paris");
        trajetFaible.setArrivalCity("Dakar");
        trajetFaible.setDepartureDate(LocalDate.of(2026, 8, 20));
        trajetFaible.setAvailableKg(new BigDecimal("8"));
        trajetFaible.setPricePerKg(new BigDecimal("20"));

        AnnouncementEntity trajetFort = new AnnouncementEntity();
        trajetFort.setId(UUID.randomUUID());
        trajetFort.setTravelerId(travelerId);
        trajetFort.setDepartureCity("Paris");
        trajetFort.setArrivalCity("Dakar");
        trajetFort.setDepartureDate(LocalDate.of(2026, 8, 10));
        trajetFort.setAvailableKg(new BigDecimal("30"));
        trajetFort.setPricePerKg(new BigDecimal("5"));

        PackageRequestEntity demande = new PackageRequestEntity();
        demande.setId(requestId);
        demande.setSenderId(UUID.randomUUID());
        demande.setStatus(PackageRequestStatus.OPEN);
        demande.setDepartureCity("Paris");
        demande.setArrivalCity("Dakar");
        demande.setDesiredDate(LocalDate.of(2026, 8, 10));
        demande.setDateToleranceDays(15);
        demande.setWeightKg(new BigDecimal("2"));
        demande.setTargetPriceEur(new BigDecimal("40"));
        demande.setCreatedAt(LocalDateTime.of(2026, 7, 1, 10, 0));

        UserEntity expediteur = new UserEntity();
        expediteur.setId(demande.getSenderId());

        when(announcementRepository.findActiveByTravelerId(travelerId))
                .thenReturn(List.of(trajetFaible, trajetFort));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Dakar"))
                .thenReturn(List.of(demande));
        when(userRepository.findById(demande.getSenderId()))
                .thenReturn(Optional.of(expediteur));

        Map<UUID, MatchingService.MatchInfo> result = service.findBestMatchByRequestId(travelerId);

        assertThat(result).hasSize(1);
        MatchingService.MatchInfo info = result.get(requestId);
        assertThat(info.tripId()).isEqualTo(trajetFort.getId());
        assertThat(info.tripDepartureDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(info.matchScore()).isGreaterThan(0);
    }

    @Test
    void findBestMatchByRequestId_ordonneParScoreDecroissant() {
        UUID travelerId = UUID.randomUUID();

        AnnouncementEntity trajet = new AnnouncementEntity();
        trajet.setId(UUID.randomUUID());
        trajet.setTravelerId(travelerId);
        trajet.setDepartureCity("Paris");
        trajet.setArrivalCity("Dakar");
        trajet.setDepartureDate(LocalDate.of(2026, 8, 10));
        trajet.setAvailableKg(new BigDecimal("30"));
        trajet.setPricePerKg(new BigDecimal("10"));

        // Budget généreux + colis léger + date exacte → score élevé.
        PackageRequestEntity forte = new PackageRequestEntity();
        forte.setId(UUID.randomUUID());
        forte.setSenderId(UUID.randomUUID());
        forte.setStatus(PackageRequestStatus.OPEN);
        forte.setDepartureCity("Paris");
        forte.setArrivalCity("Dakar");
        forte.setDesiredDate(LocalDate.of(2026, 8, 10));
        forte.setDateToleranceDays(5);
        forte.setWeightKg(new BigDecimal("1"));
        forte.setTargetPriceEur(new BigDecimal("50"));
        forte.setCreatedAt(LocalDateTime.of(2026, 7, 1, 10, 0));

        // Budget serré + colis lourd → score bas.
        PackageRequestEntity faible = new PackageRequestEntity();
        faible.setId(UUID.randomUUID());
        faible.setSenderId(UUID.randomUUID());
        faible.setStatus(PackageRequestStatus.OPEN);
        faible.setDepartureCity("Paris");
        faible.setArrivalCity("Dakar");
        faible.setDesiredDate(LocalDate.of(2026, 8, 10));
        faible.setDateToleranceDays(5);
        faible.setWeightKg(new BigDecimal("28"));
        faible.setTargetPriceEur(new BigDecimal("30"));
        faible.setCreatedAt(LocalDateTime.of(2026, 7, 1, 10, 0));

        UserEntity u1 = new UserEntity(); u1.setId(forte.getSenderId());
        UserEntity u2 = new UserEntity(); u2.setId(faible.getSenderId());

        when(announcementRepository.findActiveByTravelerId(travelerId)).thenReturn(List.of(trajet));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Dakar"))
                .thenReturn(List.of(faible, forte));
        when(userRepository.findById(forte.getSenderId())).thenReturn(Optional.of(u1));
        when(userRepository.findById(faible.getSenderId())).thenReturn(Optional.of(u2));

        Map<UUID, MatchingService.MatchInfo> result = service.findBestMatchByRequestId(travelerId);

        assertThat(result.keySet()).containsExactly(forte.getId(), faible.getId());
    }

    @Test
    void findBestMatchByRequestId_aucunTrajetActif_retourneMapVide() {
        UUID travelerId = UUID.randomUUID();
        when(announcementRepository.findActiveByTravelerId(travelerId)).thenReturn(List.of());

        assertThat(service.findBestMatchByRequestId(travelerId)).isEmpty();
    }
```

Ajouter les imports manquants en tête de fichier s'ils n'y sont pas déjà :

```java
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=MatchingServiceTest`
Expected: FAIL à la compilation — `cannot find symbol: method findBestMatchByRequestId(UUID)`.

- [ ] **Step 3: Write minimal implementation**

Dans `src/main/java/com/dony/api/matching/MatchingService.java`, ajouter le record et la méthode juste après `findMatchingRequests` (ligne 59) :

```java
    /**
     * Meilleur match par demande pour un voyageur : identifiant du trajet retenu,
     * sa date de départ, et le score de compatibilité.
     */
    public record MatchInfo(UUID requestId, UUID tripId, java.time.LocalDate tripDepartureDate, int matchScore) {}

    /**
     * Variante dédupliquée de {@link #findMatchingRequests} destinée à la recherche
     * paginée de demandes (paramètre {@code matchingMyTrips}).
     *
     * <p>{@link #findMatchingRequests} produit un DTO par couple (trajet, demande) :
     * une demande compatible avec deux trajets du voyageur y figure deux fois. Ici
     * on ne conserve qu'une entrée par demande, celle du meilleur score, et la map
     * est ordonnée par score décroissant — cet ordre porte le tri de la page.
     */
    public java.util.Map<UUID, MatchInfo> findBestMatchByRequestId(UUID travelerId) {
        List<AnnouncementEntity> activeAnnouncements =
                announcementRepository.findActiveByTravelerId(travelerId);

        java.util.Map<UUID, MatchInfo> best = new java.util.HashMap<>();

        for (AnnouncementEntity announcement : activeAnnouncements) {
            List<PackageRequestEntity> candidates = packageRequestRepository
                    .findOpenByCorridor(announcement.getDepartureCity(), announcement.getArrivalCity());

            for (PackageRequestEntity request : candidates) {
                if (!fitsWeight(request, announcement)) continue;
                if (!fitsDate(request, announcement)) continue;
                if (userRepository.findById(request.getSenderId()).isEmpty()) continue;

                int score = computeMatchScore(request, announcement, computeBudgetPerKg(request));
                MatchInfo current = best.get(request.getId());
                if (current == null || score > current.matchScore()) {
                    best.put(request.getId(), new MatchInfo(
                            request.getId(),
                            announcement.getId(),
                            announcement.getDepartureDate(),
                            score));
                }
            }
        }

        return best.values().stream()
                .sorted((a, b) -> Integer.compare(b.matchScore(), a.matchScore()))
                .collect(java.util.stream.Collectors.toMap(
                        MatchInfo::requestId,
                        m -> m,
                        (a, b) -> a,
                        java.util.LinkedHashMap::new));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=MatchingServiceTest`
Expected: PASS, y compris les tests préexistants de la classe.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dony/api/matching/MatchingService.java src/test/java/com/dony/api/matching/MatchingServiceTest.java
git commit -m "feat(matching): exposer le meilleur match par demande, dédupliqué et trié

findMatchingRequests produit un DTO par couple (trajet, demande) : une
demande compatible avec deux trajets du voyageur y figure deux fois.
findBestMatchByRequestId ne garde qu'une entrée par demande, celle du
meilleur score, ordonnée par score décroissant."
```

---

### Task 2: Specification `idIn`

**Files:**
- Modify: `src/main/java/com/dony/api/requests/specification/PackageRequestSpecifications.java`
- Test: `src/test/java/com/dony/api/requests/specification/PackageRequestSpecificationsTest.java` (créer si absent)

**Interfaces:**
- Produces: `PackageRequestSpecifications.idIn(Collection<UUID> ids)` — une collection vide produit une `Specification` qui ne matche rien (`cb.disjunction()`), jamais « tout ».

- [ ] **Step 1: Write the failing test**

Créer ou compléter `src/test/java/com/dony/api/requests/specification/PackageRequestSpecificationsTest.java` :

```java
package com.dony.api.requests.specification;

import com.dony.api.requests.entity.PackageRequestEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackageRequestSpecificationsTest {

    @Test
    void idIn_collectionVide_neMatcheRien() {
        Root<PackageRequestEntity> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Predicate rienDuTout = mock(Predicate.class);
        when(cb.disjunction()).thenReturn(rienDuTout);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.idIn(Set.of());
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isSameAs(rienDuTout);
        verify(cb).disjunction();
    }

    @Test
    void idIn_collectionNonVide_utiliseUnIn() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        Root<PackageRequestEntity> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> idPath = mock(Path.class);
        Predicate inPredicate = mock(Predicate.class);
        when(root.get("id")).thenReturn(idPath);
        when(idPath.in(any(java.util.Collection.class))).thenReturn(inPredicate);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.idIn(List.of(a, b));
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isSameAs(inPredicate);
        verify(root).get("id");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PackageRequestSpecificationsTest`
Expected: FAIL à la compilation — `cannot find symbol: method idIn`.

- [ ] **Step 3: Write minimal implementation**

Dans `PackageRequestSpecifications.java`, ajouter après `urgent(...)` :

```java
    /**
     * Restreint aux demandes dont l'id figure dans {@code ids}.
     * Une collection vide ne matche rien — jamais « tout » : sinon un voyageur
     * sans trajet actif verrait toutes les demandes de la plateforme.
     */
    public static Specification<PackageRequestEntity> idIn(java.util.Collection<java.util.UUID> ids) {
        return (root, query, cb) -> {
            if (ids == null || ids.isEmpty()) return cb.disjunction();
            return root.get("id").in(ids);
        };
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=PackageRequestSpecificationsTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dony/api/requests/specification/PackageRequestSpecifications.java src/test/java/com/dony/api/requests/specification/PackageRequestSpecificationsTest.java
git commit -m "feat(requests): ajouter la specification idIn

Collection vide = ne matche rien, jamais tout : un voyageur sans trajet
actif ne doit pas voir toutes les demandes de la plateforme."
```

---

### Task 3: Champs de match dans la réponse de recherche

Trois champs nullables, renseignés uniquement quand le filtre est actif.

**Files:**
- Modify: `src/main/java/com/dony/api/requests/dto/PackageRequestSearchResponse.java`
- Modify: le mapper `packageRequestSearchMapper` (chercher `toSearchResponse` sous `src/main/java/com/dony/api/requests/`)
- Test: `src/test/java/com/dony/api/requests/dto/` — compléter le test du mapper s'il existe, sinon la vérification passe par la Task 4.

**Interfaces:**
- Consumes: `MatchingService.MatchInfo` de la Task 1.
- Produces: `PackageRequestSearchResponse` gagne `Integer matchScore`, `UUID matchedTripId`, `LocalDate matchedTripDepartureDate` en fin de record, et `withMatch(MatchInfo)` qui retourne une copie enrichie.

- [ ] **Step 1: Write the failing test**

Créer `src/test/java/com/dony/api/requests/dto/PackageRequestSearchResponseMatchTest.java` :

```java
package com.dony.api.requests.dto;

import com.dony.api.matching.MatchingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PackageRequestSearchResponseMatchTest {

    private PackageRequestSearchResponse base(UUID id) {
        return new PackageRequestSearchResponse(
                id, "Paris", "Dakar",
                null, null, null, null,
                LocalDate.of(2026, 8, 10), 5,
                new BigDecimal("2"), null, null, null,
                new BigDecimal("40"), true, null,
                null, null,
                new PackageRequestSearchResponse.SenderPublicProfile(
                        UUID.randomUUID(), "Fatou S.", 4.9, 12, true, null),
                Set.of(), List.of(), false, false,
                null, null, null);
    }

    @Test
    void withMatch_renseigneLesTroisChampsSansToucherAuReste() {
        UUID id = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        PackageRequestSearchResponse sans = base(id);

        PackageRequestSearchResponse avec = sans.withMatch(
                new MatchingService.MatchInfo(id, tripId, LocalDate.of(2026, 8, 12), 94));

        assertThat(avec.matchScore()).isEqualTo(94);
        assertThat(avec.matchedTripId()).isEqualTo(tripId);
        assertThat(avec.matchedTripDepartureDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(avec.id()).isEqualTo(id);
        assertThat(avec.departureCity()).isEqualTo("Paris");
        assertThat(avec.sender().displayName()).isEqualTo("Fatou S.");
    }

    @Test
    void sansMatch_lesTroisChampsSontNuls() {
        PackageRequestSearchResponse sans = base(UUID.randomUUID());

        assertThat(sans.matchScore()).isNull();
        assertThat(sans.matchedTripId()).isNull();
        assertThat(sans.matchedTripDepartureDate()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PackageRequestSearchResponseMatchTest`
Expected: FAIL à la compilation — le constructeur n'accepte pas 3 arguments de plus, `withMatch` n'existe pas.

- [ ] **Step 3: Write minimal implementation**

Dans `PackageRequestSearchResponse.java`, ajouter les trois composants en **fin** de record (après `urgent`) et la méthode `withMatch` :

```java
    /** Score de compatibilité 0–100 avec le meilleur trajet actif du voyageur. Null hors filtre matchingMyTrips. */
    Integer matchScore,
    /** Trajet du voyageur retenu pour ce match. Null hors filtre matchingMyTrips. */
    UUID matchedTripId,
    /** Date de départ du trajet retenu. Null hors filtre matchingMyTrips. */
    LocalDate matchedTripDepartureDate
) {
    public record SenderPublicProfile(UUID id, String displayName, double averageRating, int totalRatings, boolean kycVerified, String avatarUrl) {}

    /** Copie enrichie des informations de match. Utilisé uniquement quand matchingMyTrips est actif. */
    public PackageRequestSearchResponse withMatch(com.dony.api.matching.MatchingService.MatchInfo info) {
        return new PackageRequestSearchResponse(
                id, departureCity, arrivalCity,
                departureLat, departureLng, arrivalLat, arrivalLng,
                desiredDate, dateToleranceDays,
                weightKg, parcelSize, transportMode, contentCategory,
                targetPriceEur, negotiable, photoUrl,
                pickupNeighborhood, deliveryNeighborhood,
                sender, acceptedPaymentMethods, photos, isFavorite, urgent,
                info.matchScore(), info.tripId(), info.tripDepartureDate());
    }
}
```

Puis corriger tous les appels au constructeur qui ne compilent plus. Les localiser :

```bash
rtk proxy grep -rn "new PackageRequestSearchResponse(" src/main src/test
```

Pour chacun, ajouter `, null, null, null` en fin d'arguments.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=PackageRequestSearchResponseMatchTest`
Expected: PASS, 2 tests.

Puis vérifier qu'aucun appelant n'est cassé :

Run: `./mvnw test`
Expected: 0 échec.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dony/api/requests/dto/PackageRequestSearchResponse.java src/test/java/com/dony/api/requests/dto/PackageRequestSearchResponseMatchTest.java
git add -u
git commit -m "feat(requests): exposer matchScore, matchedTripId et matchedTripDepartureDate

Trois champs nullables en fin de PackageRequestSearchResponse, renseignés
seulement quand matchingMyTrips est actif. withMatch produit la copie
enrichie."
```

---

### Task 4: Recherche filtrée par les trajets du voyageur

**Files:**
- Modify: `src/main/java/com/dony/api/requests/service/PackageRequestService.java:408-416`
- Test: `src/test/java/com/dony/api/requests/service/PackageRequestServiceMatchingTest.java` (créer)

**Interfaces:**
- Consumes: `MatchingService.findBestMatchByRequestId` (Task 1), `PackageRequestSpecifications.idIn` (Task 2), `PackageRequestSearchResponse.withMatch` (Task 3).
- Produces: `PackageRequestService.searchMatchingMyTrips(Specification<PackageRequestEntity> spec, Pageable pageable, UUID callerId)` retournant `Page<PackageRequestSearchResponse>` trié par `matchScore` décroissant.

`MatchingService` vit dans le package `matching`, `PackageRequestService` dans `requests`. La règle « pas d'injection de service entre packages » du CLAUDE.md vise le couplage métier bidirectionnel résolu par des events. Ici il s'agit d'une lecture synchrone dont le résultat est nécessaire pour construire la réponse HTTP : un event ne convient pas. L'injection est unidirectionnelle (`requests` → `matching`), sans cycle. Le documenter dans le Javadoc de la méthode.

- [ ] **Step 1: Write the failing test**

Créer `src/test/java/com/dony/api/requests/service/PackageRequestServiceMatchingTest.java` :

```java
package com.dony.api.requests.service;

import com.dony.api.matching.MatchingService;
import com.dony.api.requests.dto.PackageRequestSearchResponse;
import com.dony.api.requests.entity.PackageRequestEntity;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ces tests portent sur le tri, la pagination et la propagation du score.
 * Ils s'appuient sur les mocks déjà en place dans les autres tests du service ;
 * reprendre le même harnais (@ExtendWith(MockitoExtension.class), @Mock des
 * repositories, @InjectMocks du service) que PackageRequestServiceTest.
 */
class PackageRequestServiceMatchingTest {

    @Test
    void searchMatchingMyTrips_trieParScoreDecroissant() {
        // Arrange : 3 demandes, scores 40 / 94 / 70, renvoyées par le repo dans le désordre.
        // Le mock de matchingService.findBestMatchByRequestId retourne la map ordonnée
        // par score décroissant (contrat de la Task 1).
        // Le mock de repository.findAll(spec, Pageable.unpaged()) retourne les 3 entités.
        // Act : searchMatchingMyTrips(spec, PageRequest.of(0, 20), callerId)
        // Assert : les ids sortent dans l'ordre 94, 70, 40, et chaque réponse porte son score.
    }

    @Test
    void searchMatchingMyTrips_pagineApresLeTri() {
        // Arrange : 3 demandes scorées 94 / 70 / 40, page size 2.
        // Act : page 0 → [94, 70] ; page 1 → [40].
        // Assert : totalElements == 3 sur les deux pages.
    }

    @Test
    void searchMatchingMyTrips_aucunTrajetActif_retournePageVide() {
        // Arrange : findBestMatchByRequestId retourne une map vide.
        // Assert : page vide, totalElements == 0, et repository.findAll jamais appelé
        //          (court-circuit avant toute requête SQL).
    }
}
```

**Ces trois corps de test sont à écrire en entier avant de passer au step suivant**, en reprenant le harnais de mocks de `PackageRequestServiceTest` du même dossier — la structure exacte des mocks dépend des dépendances du service, qu'il faut lire. Les commentaires ci-dessus décrivent l'arrangement et les assertions exactes attendues.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PackageRequestServiceMatchingTest`
Expected: FAIL à la compilation — `cannot find symbol: method searchMatchingMyTrips`.

- [ ] **Step 3: Write minimal implementation**

Injecter `MatchingService` dans `PackageRequestService` (ajouter le paramètre au constructeur et le champ `private final`), puis ajouter la méthode à côté de `search` :

```java
    /**
     * Recherche restreinte aux demandes compatibles avec les trajets actifs du
     * voyageur, triée par score de compatibilité décroissant.
     *
     * <p>La règle de match vit dans {@link MatchingService} et n'est pas exprimable
     * en SQL sans la dupliquer : on récupère donc l'ensemble des ids compatibles,
     * on applique la recherche filtrée dessus, puis on trie et pagine en mémoire.
     * L'ensemble est borné par le nombre de matchs du voyageur, du même ordre de
     * grandeur que ce que renvoie déjà {@code GET /travelers/me/matching-requests}
     * sans pagination.
     *
     * <p>Injection {@code requests → matching} assumée : lecture synchrone
     * unidirectionnelle nécessaire à la construction de la réponse, sans cycle.
     * Un Spring Event ne conviendrait pas, le résultat étant attendu par l'appelant.
     */
    @Transactional(readOnly = true)
    public Page<PackageRequestSearchResponse> searchMatchingMyTrips(Specification<PackageRequestEntity> spec,
                                                                     Pageable pageable,
                                                                     UUID callerId) {
        Map<UUID, MatchingService.MatchInfo> matches = matchingService.findBestMatchByRequestId(callerId);
        if (matches.isEmpty()) {
            return new org.springframework.data.domain.PageImpl<>(List.of(), pageable, 0);
        }

        Specification<PackageRequestEntity> restricted = spec.and(
                PackageRequestSpecifications.idIn(matches.keySet()));

        Set<UUID> favIds = loadFavIds(callerId);
        List<PackageRequestEntity> all = repository.findAll(restricted);
        BatchMaps batch = buildBatchMaps(all);

        List<PackageRequestSearchResponse> sorted = all.stream()
                .map(e -> packageRequestSearchMapper.toSearchResponse(
                        e, favIds.contains(e.getId()), batch.userMap, batch.cityMap, batch.photoMap))
                .map(r -> r.withMatch(matches.get(r.id())))
                .sorted(java.util.Comparator.comparingInt(
                        (PackageRequestSearchResponse r) -> r.matchScore()).reversed())
                .toList();

        int from = (int) Math.min(pageable.getOffset(), sorted.size());
        int to = Math.min(from + pageable.getPageSize(), sorted.size());
        return new org.springframework.data.domain.PageImpl<>(
                sorted.subList(from, to), pageable, sorted.size());
    }
```

Ajouter l'import `com.dony.api.matching.MatchingService` et `com.dony.api.requests.specification.PackageRequestSpecifications` si absents.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=PackageRequestServiceMatchingTest`
Expected: PASS, 3 tests.

Run: `./mvnw test -Dtest=PackageRequestServiceTest`
Expected: PASS — le constructeur a changé, les tests existants doivent être mis à jour avec le mock supplémentaire.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dony/api/requests/service/PackageRequestService.java src/test/java/com/dony/api/requests/service/
git commit -m "feat(requests): recherche restreinte aux trajets actifs du voyageur

searchMatchingMyTrips réutilise MatchingService comme source de vérité de
la règle de match plutôt que de la réécrire en SQL, puis trie par score et
pagine en mémoire. Map de matchs vide = page vide sans requête SQL."
```

---

### Task 5: Paramètre `matchingMyTrips` sur l'endpoint

**Files:**
- Modify: `src/main/java/com/dony/api/requests/controller/PackageRequestController.java:108-140`
- Test: `src/test/java/com/dony/api/requests/controller/PackageRequestSearchMatchingIntegrationTest.java` (créer)

**Interfaces:**
- Consumes: `PackageRequestService.searchMatchingMyTrips` (Task 4).
- Produces: `GET /package-requests?matchingMyTrips=true` — contrat public consommé par le plan front.

- [ ] **Step 1: Write the failing test**

Créer `src/test/java/com/dony/api/requests/controller/PackageRequestSearchMatchingIntegrationTest.java`. Reprendre exactement le harnais de `MatchingRequestsEndpointTest` (annotations, mock de `FirebaseAuth`, création d'un utilisateur voyageur, header `Authorization`), qui teste déjà un endpoint voyageur.

```java
    @Test
    void search_sansMatchingMyTrips_comportementInchange() throws Exception {
        // Non-régression : une demande hors trajets du voyageur reste visible.
        mockMvc.perform(get("/package-requests")
                        .header("Authorization", "Bearer fake-token")
                        .param("departure", "Paris")
                        .param("arrival", "Dakar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].matchScore").doesNotExist())
                .andExpect(jsonPath("$.content[0].matchedTripId").doesNotExist());
    }

    @Test
    void search_matchingMyTripsTrue_renvoieLeScoreEtLeTrajet() throws Exception {
        // Arrange : le voyageur a un trajet actif Paris → Dakar le 10/08,
        // 30 kg disponibles, 10 €/kg. Une demande compatible existe (2 kg,
        // 10/08, tolérance 5 j) et une demande incompatible (Lyon → Bamako).
        mockMvc.perform(get("/package-requests")
                        .header("Authorization", "Bearer fake-token")
                        .param("matchingMyTrips", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].matchScore").isNumber())
                .andExpect(jsonPath("$.content[0].matchedTripId").exists())
                .andExpect(jsonPath("$.content[0].matchedTripDepartureDate").value("2026-08-10"));
    }

    @Test
    void search_matchingMyTripsTrue_combinableAvecLesAutresFiltres() throws Exception {
        // Arrange : deux demandes compatibles avec le trajet, l'une de 2 kg,
        // l'autre de 20 kg. maxWeight=5 ne doit laisser passer que la première.
        mockMvc.perform(get("/package-requests")
                        .header("Authorization", "Bearer fake-token")
                        .param("matchingMyTrips", "true")
                        .param("maxWeight", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].weightKg").value(2));
    }

    @Test
    void search_matchingMyTripsTrue_sansTrajetActif_renvoiePageVideSans403() throws Exception {
        // Arrange : le voyageur authentifié n'a aucun trajet ACTIVE.
        mockMvc.perform(get("/package-requests")
                        .header("Authorization", "Bearer fake-token")
                        .param("matchingMyTrips", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }
```

**Écrire les fixtures d'arrangement en entier** (persistance des `AnnouncementEntity` et `PackageRequestEntity` via les repositories, comme le fait `MatchingRequestsEndpointTest`) avant de passer au step suivant.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PackageRequestSearchMatchingIntegrationTest`
Expected: FAIL — `matchingMyTrips` est ignoré comme paramètre inconnu, `totalElements` vaut 2 au lieu de 1 et `matchScore` est absent.

- [ ] **Step 3: Write minimal implementation**

Dans `PackageRequestController.search`, ajouter le paramètre après `urgent` et l'aiguillage avant le `return` final :

```java
            @RequestParam(required = false) Boolean urgent,
            @RequestParam(required = false) Boolean matchingMyTrips,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Specification<PackageRequestEntity> spec = Specification
                .where(PackageRequestSpecifications.openOnly())
                .and(PackageRequestSpecifications.corridor(departure, arrival))
                .and(PackageRequestSpecifications.dateRange(dateFrom, dateTo))
                .and(PackageRequestSpecifications.maxWeight(maxWeight))
                .and(PackageRequestSpecifications.parcelSize(parcelSize));
        if (Boolean.TRUE.equals(urgent)) {
            spec = spec.and(PackageRequestSpecifications.urgent(config.urgency().thresholdDays()));
        }
        UUID callerId = requireUserId();
        Pageable pageable = PageRequest.of(page, size);
        // Le filtre « mes trajets » prime sur la recherche géographique : les deux
        // trient différemment (score vs distance) et ne se composent pas.
        if (Boolean.TRUE.equals(matchingMyTrips)) {
            return service.searchMatchingMyTrips(spec, pageable, callerId);
        }
        if (lat != null && lng != null) {
            double radius = radiusKm != null ? radiusKm : 50.0;
            return service.searchNearMe(spec, pageable, lat, lng, radius, callerId);
        }
        return service.search(spec, pageable, callerId);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=PackageRequestSearchMatchingIntegrationTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dony/api/requests/controller/PackageRequestController.java src/test/java/com/dony/api/requests/controller/PackageRequestSearchMatchingIntegrationTest.java
git commit -m "feat(requests): paramètre matchingMyTrips sur GET /package-requests

Restreint aux demandes compatibles avec les trajets actifs du voyageur,
triées par score. Combinable avec tous les filtres existants. Prime sur la
recherche géographique, les deux tris ne se composant pas. Absent ou false,
comportement strictement inchangé."
```

---

### Task 6: Dépréciation de l'endpoint remplacé et couverture

`GET /travelers/me/matching-requests` n'a plus de consommateur applicatif une fois le front migré. On ne le supprime pas ici : `MatchingService.findTravelersMatchingPackage` et `AlertService` dépendent du même service, et retirer un endpoint public mérite sa propre PR.

**Files:**
- Modify: `src/main/java/com/dony/api/matching/TravelerStatsController.java:128-140`
- Modify: `docs/stories-done/` — créer le document de story

- [ ] **Step 1: Marquer l'endpoint déprécié**

```java
    /**
     * @deprecated Remplacé par {@code GET /package-requests?matchingMyTrips=true},
     * qui apporte la pagination, la combinaison avec les autres filtres et la
     * déduplication par demande. Conservé le temps que les clients installés
     * migrent. Ne pas supprimer sans vérifier les versions d'app en circulation.
     */
    @Deprecated(since = "2026-07-22")
    @GetMapping("/me/matching-requests")
    public ResponseEntity<List<MatchingRequestDto>> getMatchingRequests() {
```

- [ ] **Step 2: Vérifier la suite complète**

Run: `./mvnw test`
Expected: 0 échec.

- [ ] **Step 3: Vérifier la couverture**

Run: `./mvnw test jacoco:report`
Puis ouvrir `target/site/jacoco/index.html` et vérifier ≥ 90 % global. Si le seuil n'est pas atteint sur les classes touchées, ajouter des tests jusqu'à l'atteindre avant de commit.

- [ ] **Step 4: Documenter la story**

Créer `docs/stories-done/story-matching-my-trips-filter.md` en suivant le gabarit imposé par le `CLAUDE.md` du repo : Résumé, Fichiers créés, Fichiers modifiés, Comment ça fonctionne (flux, points d'entrée API, entités, logique métier critique, pièges), Critères d'acceptation, Tests avec le pourcentage de couverture relevé, Décisions techniques.

Les pièges à y consigner : la déduplication par demande, le court-circuit sur map vide qui évite une requête SQL, la précédence sur la recherche géographique, et l'injection assumée `requests → matching`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dony/api/matching/TravelerStatsController.java docs/stories-done/story-matching-my-trips-filter.md
git commit -m "docs(matching): déprécier /travelers/me/matching-requests et documenter la story

Remplacé par GET /package-requests?matchingMyTrips=true. Conservé le temps
que les clients installés migrent."
```

---

## Récapitulatif des livrables

| Task | Livrable | Test |
|---|---|---|
| 1 | `MatchingService.findBestMatchByRequestId` + `MatchInfo` | `MatchingServiceTest` (3) |
| 2 | `PackageRequestSpecifications.idIn` | `PackageRequestSpecificationsTest` (2) |
| 3 | 3 champs de match sur le DTO + `withMatch` | `PackageRequestSearchResponseMatchTest` (2) |
| 4 | `PackageRequestService.searchMatchingMyTrips` | `PackageRequestServiceMatchingTest` (3) |
| 5 | `matchingMyTrips` sur `GET /package-requests` | `PackageRequestSearchMatchingIntegrationTest` (4) |
| 6 | Dépréciation, couverture, story | `./mvnw test` complet |

Le front consomme ce contrat à sa Task C. Ce plan est déployable seul : sans le paramètre, l'API se comporte exactement comme avant.
