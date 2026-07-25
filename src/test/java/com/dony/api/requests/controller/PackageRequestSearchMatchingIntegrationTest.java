package com.dony.api.requests.controller;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.auth.UserStatus;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.AnnouncementStatus;
import com.dony.api.matching.TransportMode;
import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
import com.dony.api.requests.entity.ParcelSize;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test d'intégration réel (vraie base, pas de mocks des repositories/services) pour le
 * paramètre {@code matchingMyTrips} sur {@code GET /package-requests}.
 *
 * <p>Harnais repris de {@link com.dony.api.matching.AnnouncementControllerIntegrationTest} —
 * le véritable équivalent "vraie base" dans ce module (cf. son propre commentaire à ce sujet
 * dans {@code PackageRequestControllerIT}, qui lui reste mock-based) : {@code @SpringBootTest}
 * + {@code @AutoConfigureMockMvc} + repositories réels autowired + authentification injectée
 * via {@code .with(authentication(...))} plutôt qu'un header Firebase.
 *
 * <p><b>Écart volontaire au brief :</b> le brief donnait des corps de test utilisant
 * {@code .header("Authorization", "Bearer fake-token")}. En profil "test",
 * {@code FirebaseConfig} n'initialise jamais {@code FirebaseApp} (pas de
 * {@code firebase.service-account-path}), donc {@code FirebaseTokenFilter.isFirebaseReady()}
 * renvoie toujours {@code false} et un header Authorization réel n'authentifierait jamais la
 * requête dans un {@code @SpringBootTest} à contexte complet — aucun test d'intégration à
 * vraie base de ce projet ne mocke {@code FirebaseAuth.getInstance()} (méthode statique) pour
 * contourner ça. On reprend donc le pattern éprouvé de {@code AnnouncementControllerIntegrationTest} :
 * {@code .with(authentication(...))} injecte directement le principal (UID Firebase) dans le
 * {@code SecurityContext}, exactement ce que produirait {@code FirebaseTokenFilter} après
 * validation d'un vrai token.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PackageRequestSearchMatchingIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired PackageRequestRepository packageRequestRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;

    private static UsernamePasswordAuthenticationToken authenticatedAs(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @BeforeEach
    void cleanDb() {
        // Ordre imposé par les FK : package_requests.sender_id et announcements.traveler_id
        // référencent users(id).
        packageRequestRepository.deleteAll();
        announcementRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ─── Test 1 : non-régression (matchingMyTrips absent) ────────────────────

    @Test
    void search_sansMatchingMyTrips_comportementInchange() throws Exception {
        // Non-régression : une demande hors trajets du voyageur reste visible.
        var traveler = seedUser("uid-nonreg-traveler", Role.TRAVELER);
        var sender = seedUser("uid-nonreg-sender", Role.SENDER);
        seedPackageRequest(sender.getId(), "Paris", "Dakar",
                LocalDate.now().plusDays(10), new BigDecimal("2"), (short) 5,
                PackageRequestStatus.OPEN);

        mockMvc.perform(get("/package-requests")
                        .with(authentication(authenticatedAs("uid-nonreg-traveler")))
                        .param("departure", "Paris")
                        .param("arrival", "Dakar"))
                .andExpect(status().isOk())
                // La demande seedée doit bien être présente dans la réponse : sans ces
                // assertions, `$.content[0].xxx` sur un tableau vide lève une
                // PathNotFoundException que `doesNotExist()` interprète comme un succès,
                // rendant le test vacueux (cf. revue).
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].departureCity").value("Paris"))
                .andExpect(jsonPath("$.content[0].arrivalCity").value("Dakar"))
                .andExpect(jsonPath("$.content[0].matchScore").doesNotExist())
                .andExpect(jsonPath("$.content[0].matchedTripId").doesNotExist());
    }

    // ─── Test 2 : matchingMyTrips=true renvoie le score et le trajet ─────────

    @Test
    void search_matchingMyTripsTrue_renvoieLeScoreEtLeTrajet() throws Exception {
        // Arrange : le voyageur a un trajet actif Paris → Dakar le 10/08,
        // 30 kg disponibles, 10 €/kg. Une demande compatible existe (2 kg,
        // 10/08, tolérance 5 j) et une demande incompatible (Lyon → Bamako).
        var traveler = seedUser("uid-match-traveler", Role.TRAVELER);
        seedAnnouncement(traveler.getId(), "Paris", "Dakar",
                LocalDate.of(2026, 8, 10), new BigDecimal("30"), new BigDecimal("10"),
                AnnouncementStatus.ACTIVE);

        var compatibleSender = seedUser("uid-match-sender-ok", Role.SENDER);
        seedPackageRequest(compatibleSender.getId(), "Paris", "Dakar",
                LocalDate.of(2026, 8, 10), new BigDecimal("2"), (short) 5,
                PackageRequestStatus.OPEN);

        var incompatibleSender = seedUser("uid-match-sender-ko", Role.SENDER);
        seedPackageRequest(incompatibleSender.getId(), "Lyon", "Bamako",
                LocalDate.of(2026, 8, 10), new BigDecimal("2"), (short) 5,
                PackageRequestStatus.OPEN);

        mockMvc.perform(get("/package-requests")
                        .with(authentication(authenticatedAs("uid-match-traveler")))
                        .param("matchingMyTrips", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].matchScore").isNumber())
                .andExpect(jsonPath("$.content[0].matchedTripId").exists())
                .andExpect(jsonPath("$.content[0].matchedTripDepartureDate").value("2026-08-10"));
    }

    // ─── Test 3 : combinable avec les autres filtres ─────────────────────────

    @Test
    void search_matchingMyTripsTrue_combinableAvecLesAutresFiltres() throws Exception {
        // Arrange : deux demandes compatibles avec le trajet, l'une de 2 kg,
        // l'autre de 20 kg. maxWeight=5 ne doit laisser passer que la première.
        var traveler = seedUser("uid-combo-traveler", Role.TRAVELER);
        seedAnnouncement(traveler.getId(), "Paris", "Dakar",
                LocalDate.of(2026, 8, 10), new BigDecimal("30"), new BigDecimal("10"),
                AnnouncementStatus.ACTIVE);

        var lightSender = seedUser("uid-combo-sender-light", Role.SENDER);
        seedPackageRequest(lightSender.getId(), "Paris", "Dakar",
                LocalDate.of(2026, 8, 10), new BigDecimal("2"), (short) 5,
                PackageRequestStatus.OPEN);

        var heavySender = seedUser("uid-combo-sender-heavy", Role.SENDER);
        seedPackageRequest(heavySender.getId(), "Paris", "Dakar",
                LocalDate.of(2026, 8, 10), new BigDecimal("20"), (short) 5,
                PackageRequestStatus.OPEN);

        mockMvc.perform(get("/package-requests")
                        .with(authentication(authenticatedAs("uid-combo-traveler")))
                        .param("matchingMyTrips", "true")
                        .param("maxWeight", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].weightKg").value(2));
    }

    // ─── Test 4 : sans trajet actif → page vide, pas de 403 ──────────────────

    @Test
    void search_matchingMyTripsTrue_sansTrajetActif_renvoiePageVideSans403() throws Exception {
        // Arrange : le voyageur authentifié n'a aucun trajet ACTIVE.
        seedUser("uid-notrip-traveler", Role.TRAVELER);

        mockMvc.perform(get("/package-requests")
                        .with(authentication(authenticatedAs("uid-notrip-traveler")))
                        .param("matchingMyTrips", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    // ─── Test 5 : trajet actif mais aucune demande compatible ────────────────

    @Test
    void search_matchingMyTripsTrue_trajetActifMaisAucuneDemandeCompatible_renvoiePageVide()
            throws Exception {
        // Arrange : le voyageur a bien un trajet actif Paris → Dakar, mais la seule
        // demande de la base est sur un autre corridor. Distinct du test 4, qui couvre
        // l'absence totale de trajet actif.
        var traveler = seedUser("uid-nomatch-traveler", Role.TRAVELER);
        seedAnnouncement(traveler.getId(), "Paris", "Dakar",
                LocalDate.of(2026, 8, 10), new BigDecimal("30"), new BigDecimal("10"),
                AnnouncementStatus.ACTIVE);

        var sender = seedUser("uid-nomatch-sender", Role.SENDER);
        seedPackageRequest(sender.getId(), "Lyon", "Bamako",
                LocalDate.of(2026, 8, 10), new BigDecimal("2"), (short) 5,
                PackageRequestStatus.OPEN);

        mockMvc.perform(get("/package-requests")
                        .with(authentication(authenticatedAs("uid-nomatch-traveler")))
                        .param("matchingMyTrips", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    // ─── Test 6 : une demande NEGOTIATING reste visible ──────────────────────

    @Test
    void search_matchingMyTripsTrue_inclutLesDemandesEnNegociation() throws Exception {
        // Le statut passe à NEGOTIATING dès qu'un voyageur quelconque ouvre un fil.
        // La liste filtrée doit rester alignée sur la recherche standard (OPEN +
        // NEGOTIATING), sinon la demande disparaît de la liste du voyageur qui négocie.
        var traveler = seedUser("uid-nego-traveler", Role.TRAVELER);
        seedAnnouncement(traveler.getId(), "Paris", "Dakar",
                LocalDate.of(2026, 8, 10), new BigDecimal("30"), new BigDecimal("10"),
                AnnouncementStatus.ACTIVE);

        var sender = seedUser("uid-nego-sender", Role.SENDER);
        var demande = seedPackageRequest(sender.getId(), "Paris", "Dakar",
                LocalDate.of(2026, 8, 10), new BigDecimal("2"), (short) 5,
                PackageRequestStatus.NEGOTIATING);

        mockMvc.perform(get("/package-requests")
                        .with(authentication(authenticatedAs("uid-nego-traveler")))
                        .param("matchingMyTrips", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(demande.getId().toString()))
                .andExpect(jsonPath("$.content[0].matchScore").isNumber());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private UserEntity seedUser(String firebaseUid, Role role) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid(firebaseUid);
        // Dérivé du firebaseUid (pas une constante) : plusieurs utilisateurs sont seedés par
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(new java.util.HashSet<>(List.of(role)));
        return userRepository.save(user);
    }

    private AnnouncementEntity seedAnnouncement(UUID travelerId, String departureCity, String arrivalCity,
                                                 LocalDate departureDate, BigDecimal availableKg,
                                                 BigDecimal pricePerKg, AnnouncementStatus status) {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(travelerId);
        e.setDepartureCity(departureCity);
        e.setArrivalCity(arrivalCity);
        e.setDepartureDate(departureDate);
        e.setAvailableKg(availableKg);
        e.setTotalKg(availableKg);
        e.setPricePerKg(pricePerKg);
        e.setStatus(status);
        e.setTransportMode(TransportMode.PLANE);
        e.setPickupAddressLabel("Test pickup");
        e.setPickupLat(BigDecimal.valueOf(48.8566));
        e.setPickupLng(BigDecimal.valueOf(2.3522));
        e.setDeliveryAddressLabel("Test delivery");
        e.setDeliveryLat(BigDecimal.valueOf(14.6928));
        e.setDeliveryLng(BigDecimal.valueOf(-17.4467));
        return announcementRepository.save(e);
    }

    private PackageRequestEntity seedPackageRequest(UUID senderId, String departureCity, String arrivalCity,
                                                      LocalDate desiredDate, BigDecimal weightKg,
                                                      short dateToleranceDays, PackageRequestStatus status) {
        PackageRequestEntity e = new PackageRequestEntity();
        e.setSenderId(senderId);
        e.setDepartureCity(departureCity);
        e.setArrivalCity(arrivalCity);
        e.setDesiredDate(desiredDate);
        e.setDateToleranceDays(dateToleranceDays);
        e.setWeightKg(weightKg);
        e.setParcelSize(ParcelSize.SMALL);
        e.setTransportMode(TransportMode.PLANE);
        e.setContentCategory("vetements");
        e.setNegotiable(true);
        e.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
        e.setStatus(status);
        return packageRequestRepository.save(e);
    }
}
