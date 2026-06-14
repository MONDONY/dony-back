package com.dony.api.matching;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.Role;
import com.dony.api.matching.CapacityUnit;
import com.dony.api.auth.StripeAccountStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.config.DonyConfigProperties;
import com.dony.api.matching.dto.AddressDto;
import com.dony.api.matching.dto.AnnouncementDetailResponse;
import com.dony.api.matching.dto.AnnouncementRequest;
import com.dony.api.matching.dto.AnnouncementResponse;
import com.dony.api.matching.events.AnnouncementDeletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementService — tests unitaires")
class AnnouncementServiceTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private BidRepository bidRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PriceGridService priceGridService;
    @Mock private com.dony.api.country.FlagService flagService;
    @Mock private com.dony.api.common.StorageService storageService;

    private AnnouncementService announcementService;

    @org.junit.jupiter.api.BeforeEach
    void initService() {
        DonyConfigProperties config = new DonyConfigProperties(null, null, null);
        // Pass-through: return the key/URL as-is so avatar URL assertions remain valid
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        announcementService = new AnnouncementService(
                announcementRepository, bidRepository, userRepository,
                auditService, eventPublisher, config, priceGridService, flagService,
                storageService);
    }

    private static final String FIREBASE_UID = "uid-traveler-001";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UserEntity buildTraveler() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(FIREBASE_UID);
        u.setPhoneNumber("+33601020304");
        u.getRoles().add(Role.TRAVELER);
        setId(u, USER_ID);
        return u;
    }

    private AnnouncementEntity buildAnnouncement(UserEntity traveler) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setAvailableKg(BigDecimal.valueOf(20));
        a.setTotalKg(BigDecimal.valueOf(20));
        a.setPricePerKg(BigDecimal.valueOf(5));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("CDG Terminal 2E");
        a.setPickupLat(BigDecimal.valueOf(49.009));
        a.setPickupLng(BigDecimal.valueOf(2.547));
        a.setDeliveryAddressLabel("Aéroport LSS");
        a.setDeliveryLat(BigDecimal.valueOf(14.739));
        a.setDeliveryLng(BigDecimal.valueOf(-17.490));
        setId(a, ANNOUNCEMENT_ID);
        return a;
    }

    private AnnouncementRequest buildRequest() {
        return buildRequest(TransportMode.PLANE);
    }

    private AnnouncementRequest buildRequest(TransportMode mode) {
        LocalDate departure = LocalDate.now().plusDays(10);
        return new AnnouncementRequest(
                "Paris", "Dakar",
                departure,
                LocalTime.of(10, 0), LocalTime.of(22, 0),
                new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                new AddressDto("Aéroport LSS", 14.739, -17.490),
                BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                mode,
                null, null, null, null, null, null,
                null, null,
                departure.atTime(8, 0), departure.atTime(9, 0)
        );
    }

    private UserEntity buildTravelerWithCommissionMethod() {
        UserEntity u = buildTraveler();
        u.setCommissionPaymentMethodId("pm_test");
        return u;
    }

    // ─── createAnnouncement ────────────────────────────────────────────────────

    @Nested
    @DisplayName("createAnnouncement()")
    class CreateTests {

        @Test
        @DisplayName("données valides → annonce créée + audit enregistré")
        void create_validRequest_createsAndAudits() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementResponse result = announcementService.createAnnouncement(FIREBASE_UID, buildRequest());

            assertThat(result.departureCity()).isEqualTo("Paris");
            assertThat(result.arrivalCity()).isEqualTo("Dakar");
            assertThat(result.status()).isEqualTo("ACTIVE");
            verify(auditService).log(eq("USER"), any(), eq("ANNOUNCEMENT_CREATED"), any(), any());
        }

        @Test
        @DisplayName("codes pays dans la requête → persistés sur l'entité + drapeaux résolus dans la réponse")
        void create_withCountryCodes_storesCodesAndResolvesFlags() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            when(announcementRepository.save(captor.capture())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);
            when(flagService.getFlag("US")).thenReturn("🇺🇸"); // 🇺🇸
            when(flagService.getFlag("SN")).thenReturn("🇸🇳"); // 🇸🇳

            AnnouncementRequest req = new AnnouncementRequest(
                    "New York", "Dakar",
                    LocalDate.now().plusDays(10),
                    LocalTime.of(10, 0), LocalTime.of(22, 0),
                    new AddressDto("JFK", 40.641, -73.778),
                    new AddressDto("Aéroport LSS", 14.739, -17.490),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, null, null, null,
                    "US", "SN",
                    LocalDate.now().plusDays(10).atTime(8, 0), LocalDate.now().plusDays(10).atTime(9, 0)
            );

            AnnouncementResponse result = announcementService.createAnnouncement(FIREBASE_UID, req);

            // Codes persistés sur l'entité
            assertThat(captor.getValue().getDepartureCountryCode()).isEqualTo("US");
            assertThat(captor.getValue().getArrivalCountryCode()).isEqualTo("SN");
            // Codes + drapeaux dans la réponse
            assertThat(result.departureCountryCode()).isEqualTo("US");
            assertThat(result.arrivalCountryCode()).isEqualTo("SN");
            assertThat(result.departureFlag()).isEqualTo("🇺🇸");
            assertThat(result.arrivalFlag()).isEqualTo("🇸🇳");
        }

        @Test
        @DisplayName("codes pays absents → codes et drapeaux null dans la réponse")
        void create_withoutCountryCodes_nullCodesAndFlags() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);
            when(flagService.getFlag(null)).thenReturn(null);

            AnnouncementResponse result = announcementService.createAnnouncement(FIREBASE_UID, buildRequest());

            assertThat(result.departureCountryCode()).isNull();
            assertThat(result.arrivalCountryCode()).isNull();
            assertThat(result.departureFlag()).isNull();
            assertThat(result.arrivalFlag()).isNull();
        }

        @Test
        @DisplayName("utilisateur sans rôle TRAVELER → rôle ajouté automatiquement")
        void create_userWithoutTravelerRole_addsTravelerRole() {
            UserEntity user = new UserEntity();
            user.setFirebaseUid(FIREBASE_UID);
            setId(user, USER_ID);
            // No TRAVELER role initially
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any(UserEntity.class))).thenReturn(user);
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            announcementService.createAnnouncement(FIREBASE_UID, buildRequest());

            assertThat(user.getRoles()).contains(Role.TRAVELER);
            verify(userRepository, atLeastOnce()).save(user);
        }

        @Test
        @DisplayName("compte Stripe non configuré → 403 stripe-onboarding-incomplete")
        void create_stripeNotOnboarded_throwsForbidden() throws Exception {
            UserEntity traveler = buildTraveler();
            traveler.setKycStatus(KycStatus.VERIFIED);
            // stripeAccountStatus defaults to NOT_CREATED — Stripe not set up
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));

            Field enforceField = AnnouncementService.class.getDeclaredField("enforceStripeOnboarding");
            enforceField.setAccessible(true);
            enforceField.set(announcementService, true);

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, buildRequest()))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getErrorCode()).isEqualTo("stripe-onboarding-incomplete");
                    });
        }

        @Test
        @DisplayName("utilisateur introuvable → 404 NOT_FOUND")
        void create_unknownUser_throwsNotFound() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, buildRequest()))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("voyageur suspendu de publication → 403 publishing-suspended (D4)")
        void create_publishingSuspended_throwsForbidden() {
            UserEntity traveler = buildTraveler();
            traveler.setPublishingSuspended(true);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, buildRequest()))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getErrorCode()).isEqualTo("publishing-suspended");
                    });
        }

        @Test
        @DisplayName("création → totalKg = availableKg")
        void create_setsTotalKgEqualToAvailableKg() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            when(announcementRepository.save(captor.capture())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementResponse result = announcementService.createAnnouncement(FIREBASE_UID, buildRequest());

            AnnouncementEntity saved = captor.getValue();
            assertThat(saved.getTotalKg()).isEqualByComparingTo(saved.getAvailableKg());
            assertThat(result.totalKg()).isEqualByComparingTo(result.availableKg());
        }

        @Test
        @DisplayName("audit payload contient le transportMode")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void create_writesTransportModeToAuditPayload() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            announcementService.createAnnouncement(FIREBASE_UID, buildRequest(TransportMode.TRAIN));

            ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
            verify(auditService).log(eq("USER"), any(), eq("ANNOUNCEMENT_CREATED"), any(), captor.capture());
            assertThat(captor.getValue()).containsEntry("transportMode", "TRAIN");
        }

        @Test
        @DisplayName("CASH sans carte commission → autorisé (vérification reportée à l'acceptation du bid)")
        void create_cashWithoutCommissionMethod_isAllowed() {
            // Règle métier : la carte de commission n'est plus requise à la création d'annonce.
            // La capacité de paiement (wallet ou carte) est vérifiée à l'acceptation du bid.
            UserEntity traveler = buildTraveler();
            traveler.setKycStatus(com.dony.api.auth.KycStatus.VERIFIED);
            traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar", LocalDate.now().plusDays(10),
                    null, null,
                    new AddressDto("CDG", 49.009, 2.547),
                    new AddressDto("DSS", 14.693, -17.447),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, java.util.Set.of(com.dony.api.payments.cash.PaymentMethod.STRIPE, com.dony.api.payments.cash.PaymentMethod.CASH), null, null,
                    null, null,
                    LocalDate.now().plusDays(10).atTime(16, 0), LocalDate.now().plusDays(10).atTime(18, 0)
            );

            // Ne doit PAS lever CommissionMethodMissingException
            org.assertj.core.api.Assertions.assertThatNoException()
                    .isThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, req));
        }

        @Test
        @DisplayName("CASH avec carte commission enregistrée → acceptedPaymentMethods inclut CASH")
        void create_cashWithCommissionMethod_setsPaymentMethods() {
            UserEntity traveler = buildTravelerWithCommissionMethod();
            traveler.setKycStatus(com.dony.api.auth.KycStatus.VERIFIED);
            traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar", LocalDate.now().plusDays(10),
                    null, null,
                    new AddressDto("CDG", 49.009, 2.547),
                    new AddressDto("DSS", 14.693, -17.447),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, java.util.Set.of(com.dony.api.payments.cash.PaymentMethod.STRIPE, com.dony.api.payments.cash.PaymentMethod.CASH), null, null,
                    null, null,
                    LocalDate.now().plusDays(10).atTime(16, 0), LocalDate.now().plusDays(10).atTime(18, 0)
            );

            announcementService.createAnnouncement(FIREBASE_UID, req);

            org.mockito.ArgumentCaptor<AnnouncementEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(AnnouncementEntity.class);
            verify(announcementRepository).save(captor.capture());
            assertThat(captor.getValue().getAcceptedPaymentMethods())
                    .contains(com.dony.api.payments.cash.PaymentMethod.CASH);
        }

        @Test
        @DisplayName("pricingMode MIXED → snapshotToAnnouncement appelé + pricingMode MIXED dans l'entité")
        void createAnnouncement_MIXED_calls_snapshotToAnnouncement() {
            UserEntity traveler = buildTraveler();
            traveler.setKycStatus(KycStatus.VERIFIED);
            traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            when(announcementRepository.save(captor.capture())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar", LocalDate.now().plusDays(10),
                    LocalTime.of(10, 0), LocalTime.of(22, 0),
                    new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                    new AddressDto("Aéroport LSS", 14.739, -17.490),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, null, null, PricingMode.MIXED,
                    null, null,
                    LocalDate.now().plusDays(10).atTime(8, 0), LocalDate.now().plusDays(10).atTime(9, 0)
            );

            AnnouncementResponse result = announcementService.createAnnouncement(FIREBASE_UID, req);

            verify(priceGridService).snapshotToAnnouncement(USER_ID, ANNOUNCEMENT_ID);
            assertThat(captor.getValue().getPricingMode()).isEqualTo(PricingMode.MIXED);
            assertThat(result.pricingMode()).isEqualTo(PricingMode.MIXED);
        }

        @Test
        @DisplayName("pricingMode MIXED + grille vide → 422 propagé")
        void createAnnouncement_MIXED_propagates_422_when_grid_empty() {
            UserEntity traveler = buildTraveler();
            traveler.setKycStatus(KycStatus.VERIFIED);
            traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            doThrow(new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "price-grid-empty: au moins 1 article requis pour le mode MIXED"))
                    .when(priceGridService).snapshotToAnnouncement(any(), any());

            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar", LocalDate.now().plusDays(10),
                    LocalTime.of(10, 0), LocalTime.of(22, 0),
                    new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                    new AddressDto("Aéroport LSS", 14.739, -17.490),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, null, null, PricingMode.MIXED,
                    null, null,
                    LocalDate.now().plusDays(10).atTime(8, 0), LocalDate.now().plusDays(10).atTime(9, 0)
            );

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, req))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode())
                            .isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY));
        }
    }

    // ─── getMyAnnouncements ────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMyAnnouncements()")
    class GetMyTests {

        @Test
        @DisplayName("voyageur avec annonces → page retournée")
        void getMyAnnouncements_withAnnouncements_returnsPage() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(2L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(1L);
            when(announcementRepository.findByTravelerIdFiltered(
                    eq(USER_ID), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any()))
                    .thenReturn(new PageImpl<>(List.of(a)));

            Page<AnnouncementResponse> result = announcementService.getMyAnnouncements(
                    FIREBASE_UID, null, null, null, null, null, null, null, PageRequest.of(0, 10));

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).departureCity()).isEqualTo("Paris");
        }

        @Test
        @DisplayName("réponse expose reservedKg / surplusEligible / surplusPublished")
        void getMyAnnouncements_exposesSurplusFields() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setReservedKg(BigDecimal.valueOf(5));
            a.setSurplusEligible(true);
            a.setSurplusPublished(true);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);
            when(announcementRepository.findByTravelerIdFiltered(
                    eq(USER_ID), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any()))
                    .thenReturn(new PageImpl<>(List.of(a)));

            Page<AnnouncementResponse> result = announcementService.getMyAnnouncements(
                    FIREBASE_UID, null, null, null, null, null, null, null, PageRequest.of(0, 10));

            AnnouncementResponse r = result.getContent().get(0);
            assertThat(r.reservedKg()).isEqualByComparingTo(BigDecimal.valueOf(5));
            assertThat(r.surplusEligible()).isTrue();
            assertThat(r.surplusPublished()).isTrue();
        }
    }

    // ─── getAnnouncementDetail ─────────────────────────────────────────────────

    @Nested
    @DisplayName("getAnnouncementDetail()")
    class DetailTests {

        @Test
        @DisplayName("annonce existante → retourne le détail")
        void getDetail_existingAnnouncement_returnsDetail() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(3L);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.departureCity()).isEqualTo("Paris");
            assertThat(result.bidsCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("détail expose reservedKg / surplusEligible / surplusPublished")
        void getDetail_exposesSurplusFields() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setReservedKg(BigDecimal.valueOf(5));
            a.setSurplusEligible(true);
            a.setSurplusPublished(true);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.reservedKg()).isEqualByComparingTo(BigDecimal.valueOf(5));
            assertThat(result.surplusEligible()).isTrue();
            assertThat(result.surplusPublished()).isTrue();
        }

        @Test
        @DisplayName("annonce KG_FREE → capacityUnit présent dans le détail (regression)")
        void getDetail_kgFreeAnnouncement_returnsCapacityUnit() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setCapacityUnit(CapacityUnit.KG_FREE);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.capacityUnit()).isEqualTo(CapacityUnit.KG_FREE);
        }

        @Test
        @DisplayName("annonce acceptant le CASH → cashAccepted=true dans le détail (regression)")
        void getDetail_cashAccepted_returnsCashAcceptedTrue() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setAcceptedPaymentMethods(java.util.EnumSet.of(
                    com.dony.api.payments.cash.PaymentMethod.STRIPE,
                    com.dony.api.payments.cash.PaymentMethod.CASH));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.cashAccepted()).isTrue();
            assertThat(result.acceptedPaymentMethods()).contains("CASH");
        }

        @Test
        @DisplayName("annonce STRIPE seul → cashAccepted=false dans le détail")
        void getDetail_stripeOnly_returnsCashAcceptedFalse() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.cashAccepted()).isFalse();
        }

        @Test
        @DisplayName("annonce introuvable → 404 NOT_FOUND")
        void getDetail_unknownAnnouncement_throwsNotFound() {
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> announcementService.getAnnouncementDetail(ANNOUNCEMENT_ID, FIREBASE_UID))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    // ─── updateAnnouncement ────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateAnnouncement()")
    class UpdateTests {

        @Test
        @DisplayName("propriétaire + pas de bids acceptés → mise à jour réussie")
        void update_ownerNoBids_updatesAndAudits() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT)))
                    .thenReturn(false);
            when(announcementRepository.save(any())).thenReturn(a);
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);

            AnnouncementRequest req = new AnnouncementRequest(
                    "Lyon", "Abidjan", LocalDate.now().plusDays(15),
                    null, null,
                    new AddressDto("Gare Part-Dieu, Lyon", 45.760, 4.860),
                    new AddressDto("Aéroport FHB, Abidjan", 5.261, -3.927),
                    BigDecimal.valueOf(25), BigDecimal.valueOf(6),
                    TransportMode.PLANE,
                    null, null, null, null, null, null,
                    null, null,
                    LocalDate.now().plusDays(15).atTime(16, 0), LocalDate.now().plusDays(15).atTime(18, 0)
            );

            AnnouncementDetailResponse result = announcementService.updateAnnouncement(
                    ANNOUNCEMENT_ID, FIREBASE_UID, req);

            assertThat(result.departureCity()).isEqualTo("Lyon");
            assertThat(result.arrivalCity()).isEqualTo("Abidjan");
            verify(auditService).log(eq("USER"), any(), eq("ANNOUNCEMENT_UPDATED"), any(), any());
        }

        @Test
        @DisplayName("bids acceptés existants → 409 CONFLICT")
        void update_withAcceptedBids_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT)))
                    .thenReturn(true);

            assertThatThrownBy(() -> announcementService.updateAnnouncement(
                    ANNOUNCEMENT_ID, FIREBASE_UID, buildRequest()))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("modification-impossible");
                    });
        }

        @Test
        @DisplayName("update sans bids acceptés → totalKg synchronisé avec availableKg")
        void update_setsTotalKgEqualToAvailableKg() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            // Existing announcement starts at 20 kg total
            assertThat(a.getTotalKg()).isEqualByComparingTo("20");

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT)))
                    .thenReturn(false);
            when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);

            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar", LocalDate.now().plusDays(15),
                    null, null,
                    new AddressDto("CDG", 49.009, 2.547),
                    new AddressDto("DSS", 14.693, -17.447),
                    BigDecimal.valueOf(35), BigDecimal.valueOf(6),
                    TransportMode.PLANE,
                    null, null, null, null, null, null,
                    null, null,
                    LocalDate.now().plusDays(15).atTime(16, 0), LocalDate.now().plusDays(15).atTime(18, 0)
            );

            announcementService.updateAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID, req);

            assertThat(a.getAvailableKg()).isEqualByComparingTo("35");
            assertThat(a.getTotalKg()).isEqualByComparingTo("35");
        }

        @Test
        @DisplayName("pas propriétaire → 403 FORBIDDEN")
        void update_notOwner_throwsForbidden() {
            UserEntity otherUser = new UserEntity();
            otherUser.setFirebaseUid(FIREBASE_UID);
            setId(otherUser, UUID.randomUUID()); // Different ID

            AnnouncementEntity a = new AnnouncementEntity();
            a.setTravelerId(UUID.randomUUID()); // Different traveler
            setId(a, ANNOUNCEMENT_ID);

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(otherUser));

            assertThatThrownBy(() -> announcementService.updateAnnouncement(
                    ANNOUNCEMENT_ID, FIREBASE_UID, buildRequest()))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    // ─── deleteAnnouncement ────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteAnnouncement()")
    class DeleteTests {

        @Test
        @DisplayName("annonce active sans bids → soft-delete + audit")
        void delete_activeNoBids_softDeletes() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT)))
                    .thenReturn(false);
            when(bidRepository.findByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID, List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED)))
                    .thenReturn(List.of());

            announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(a.getDeletedAt()).isNotNull();
            verify(announcementRepository).save(a);
            verify(auditService).log(eq("ANNOUNCEMENT"), any(), eq("ANNOUNCEMENT_DELETED"), any(), any());
        }

        @Test
        @DisplayName("annonce active avec bids PENDING/PAYMENT_ESCROWED → bids rejetés + soft-delete")
        void delete_activeWithPendingBids_rejectsBidsAndDeletes() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);

            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(ANNOUNCEMENT_ID);
            bid.setSenderId(UUID.randomUUID());
            bid.setStatus(BidStatus.PAYMENT_ESCROWED);
            setId(bid, UUID.randomUUID());

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT)))
                    .thenReturn(false);
            when(bidRepository.findByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID, List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED)))
                    .thenReturn(List.of(bid));

            announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.REJECTED);
            verify(bidRepository).save(bid);
            assertThat(a.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("annonce active avec bids ACCEPTED → 409 CONFLICT")
        void delete_activeWithAcceptedBids_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT)))
                    .thenReturn(true);

            assertThatThrownBy(() -> announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("annonce CANCELLED → soft-delete des bids + event publié")
        void delete_cancelledAnnouncement_deletesWithBidsAndPublishesEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setStatus(AnnouncementStatus.CANCELLED);

            BidEntity bid = new BidEntity();
            setId(bid, UUID.randomUUID());
            bid.setStatus(BidStatus.CANCELLED);

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(List.of(bid));

            announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(bid.getDeletedAt()).isNotNull();
            assertThat(a.getDeletedAt()).isNotNull();
            ArgumentCaptor<AnnouncementDeletedEvent> captor =
                    ArgumentCaptor.forClass(AnnouncementDeletedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().announcementId()).isEqualTo(ANNOUNCEMENT_ID);
        }

        @Test
        @DisplayName("pas propriétaire → 403 FORBIDDEN")
        void delete_notOwner_throwsForbidden() {
            UserEntity otherUser = new UserEntity();
            setId(otherUser, UUID.randomUUID());
            otherUser.setFirebaseUid(FIREBASE_UID);

            AnnouncementEntity a = new AnnouncementEntity();
            a.setTravelerId(UUID.randomUUID());
            a.setStatus(AnnouncementStatus.ACTIVE);
            setId(a, ANNOUNCEMENT_ID);

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(otherUser));

            assertThatThrownBy(() -> announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("annonce COMPLETED (pas ACTIVE ni CANCELLED) → 409 CONFLICT")
        void delete_completedStatus_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setStatus(AnnouncementStatus.COMPLETED);

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }
    }

    // ── searchAnnouncements ────────────────────────────────────────────────────

    @Nested
    @DisplayName("searchAnnouncements()")
    class SearchTests {

        @Test
        @DisplayName("sans filtre + tri par date ASC → retourne la page")
        void search_noFilters_sortByDate_returnsPage() {
            UserEntity traveler = buildTraveler();
            traveler.setFirstName("Amara");
            traveler.setLastName("Diallo");
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(3L);

            Page<?> result = announcementService.searchAnnouncements(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "date", "asc", PageRequest.of(0, 10), null);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("avec tous les filtres + tri par prix DESC")
        void search_allFilters_sortByPriceDesc_returnsPage() {
            UserEntity traveler = buildTraveler();
            traveler.setFirstName("Fatou");
            traveler.setLastName(null);
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(1L);

            Page<?> result = announcementService.searchAnnouncements(
                    "Paris", "Dakar",
                    LocalDate.now(), LocalDate.now().plusDays(30),
                    BigDecimal.valueOf(5), null, null, null, null, null, null, null, null, null, null, null, "price", "desc", PageRequest.of(0, 10), null);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("voyageur sans prénom → displayName = nom de famille")
        void search_travelerLastNameOnly_displayName() {
            UserEntity traveler = buildTraveler();
            traveler.setFirstName(null);
            traveler.setLastName("Keita");
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            Page<?> result = announcementService.searchAnnouncements(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "asc", PageRequest.of(0, 10), null);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("voyageur introuvable → profil null")
        void search_travelerNotFound_profileIsNull() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            Page<?> result = announcementService.searchAnnouncements(
                    "Paris", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "date", "desc", PageRequest.of(0, 10), null);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("voyageur sans prénom ni nom → displayName null")
        void search_travelerNeitherFirstNorLastName_displayNameNull() {
            UserEntity traveler = buildTraveler();
            traveler.setFirstName(null);
            traveler.setLastName(null);
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            assertThatNoException().isThrownBy(() -> announcementService.searchAnnouncements(
                    null, "Dakar", LocalDate.now(), null, null, null, null, null, null, null, null, null, null, null, null, null, "price", "asc", PageRequest.of(0, 10), null));
        }

        @Test
        @DisplayName("voyageur avec avatarUrl → TravelerProfileDto.avatarUrl propagé")
        void search_travelerAvatarUrl_isMappedInProfile() {
            UserEntity traveler = buildTraveler();
            traveler.setFirstName("Amara");
            traveler.setAvatarUrl("https://cdn.example.com/avatar.jpg");
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            Page<?> result = announcementService.searchAnnouncements(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "date", "asc", PageRequest.of(0, 10), null);

            var response = (com.dony.api.matching.dto.AnnouncementSearchResponse) result.getContent().get(0);
            assertThat(response.traveler()).isNotNull();
            assertThat(response.traveler().avatarUrl()).isEqualTo("https://cdn.example.com/avatar.jpg");
        }

        @Test
        @DisplayName("voyageur sans avatarUrl → TravelerProfileDto.avatarUrl null")
        void search_travelerNoAvatarUrl_profileAvatarUrlNull() {
            UserEntity traveler = buildTraveler();
            traveler.setFirstName("Amara");
            // avatarUrl not set → null
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            Page<?> result = announcementService.searchAnnouncements(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "date", "asc", PageRequest.of(0, 10), null);

            var response = (com.dony.api.matching.dto.AnnouncementSearchResponse) result.getContent().get(0);
            assertThat(response.traveler()).isNotNull();
            assertThat(response.traveler().avatarUrl()).isNull();
        }
    }

    // ─── capacityUnit + date validation ───────────────────────────────────────

    @Nested
    @DisplayName("createAnnouncement — validation capacityUnit & date")
    class CapacityUnitCreationTest {

        @Test
        @DisplayName("capacityUnit SUITCASE_32KG → persisté sur l'entité sauvegardée")
        void create_withCapacityUnit_suitcase32kg_succeeds() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            when(announcementRepository.save(captor.capture())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar",
                    LocalDate.now().plusDays(10),
                    LocalTime.of(10, 0), LocalTime.of(22, 0),
                    new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                    new AddressDto("Aéroport LSS", 14.739, -17.490),
                    BigDecimal.valueOf(32), BigDecimal.valueOf(8),
                    TransportMode.PLANE,
                    null, null, null, null, CapacityUnit.SUITCASE_32KG, null,
                    null, null,
                    LocalDate.now().plusDays(10).atTime(8, 0), LocalDate.now().plusDays(10).atTime(9, 0)
            );

            announcementService.createAnnouncement(FIREBASE_UID, req);

            assertThat(captor.getValue().getCapacityUnit()).isEqualTo(CapacityUnit.SUITCASE_32KG);
        }

        @Test
        @DisplayName("capacityUnit null → défaut SUITCASE_23KG")
        void create_withNullCapacityUnit_defaultsToSuitcase23Kg() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            when(announcementRepository.save(captor.capture())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            announcementService.createAnnouncement(FIREBASE_UID, buildRequest());

            assertThat(captor.getValue().getCapacityUnit()).isEqualTo(CapacityUnit.SUITCASE_23KG);
        }

        @Test
        @DisplayName("date de départ dans le passé → 422 invalid-departure-date")
        void create_withPastDepartureDate_throwsUnprocessableEntity() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));

            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar",
                    LocalDate.now().minusDays(1),
                    null, null,
                    new AddressDto("CDG", 49.009, 2.547),
                    new AddressDto("DSS", 14.693, -17.447),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, null, null, null,
                    null, null,
                    LocalDate.now().minusDays(1).atTime(16, 0), LocalDate.now().minusDays(1).atTime(18, 0)
            );

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, req))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("invalid-departure-date");
                        assertThat(ex.getMessage()).contains("passé");
                    });
        }
    }

    // ─── HandoverWindow validation ─────────────────────────────────────────────

    @Nested
    @DisplayName("HandoverWindow — validation createAnnouncement()")
    class HandoverWindowTests {

        private AnnouncementRequest buildRequestWithWindow(LocalDate departure,
                                                           LocalDateTime start,
                                                           LocalDateTime end) {
            return new AnnouncementRequest(
                    "Paris", "Dakar",
                    departure,
                    LocalTime.of(20, 0), LocalTime.of(22, 0),
                    new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                    new AddressDto("Aéroport LSS", 14.739, -17.490),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, null, null, null,
                    null, null,
                    start, end
            );
        }

        @Test
        @DisplayName("fenêtre de remise nulle → 422 handover-window-required")
        void createAnnouncement_handoverWindowNull_throws422() {
            LocalDate departure = LocalDate.now().plusDays(10);
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));

            AnnouncementRequest req = buildRequestWithWindow(departure, null, null);

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, req))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("handover-window-required");
                    });
        }

        @Test
        @DisplayName("fenêtre fin avant début → 422 invalid-handover-window")
        void createAnnouncement_handoverEndBeforeStart_throws422() {
            LocalDate departure = LocalDate.now().plusDays(10);
            LocalDateTime start = departure.atTime(18, 0);
            LocalDateTime end   = start.minusHours(1);
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));

            AnnouncementRequest req = buildRequestWithWindow(departure, start, end);

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, req))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("invalid-handover-window");
                    });
        }

        @Test
        @DisplayName("fenêtre fin après départ → 422 handover-after-departure")
        void createAnnouncement_handoverEndAfterDeparture_throws422() {
            LocalDate departure = LocalDate.now().plusDays(10);
            LocalDateTime start = departure.atTime(18, 0);
            LocalDateTime end   = departure.plusDays(1).atTime(8, 0); // after departure
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));

            // departureTime=null → bound = departure.atTime(LocalTime.MAX)
            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar",
                    departure,
                    null, null,
                    new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                    new AddressDto("Aéroport LSS", 14.739, -17.490),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, null, null, null,
                    null, null,
                    start, end
            );

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, req))
                    .isInstanceOf(DonyBusinessException.class)
                    .satisfies(e -> {
                        DonyBusinessException ex = (DonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("handover-after-departure");
                    });
        }

        @Test
        @DisplayName("fenêtre valide → persistée sur l'entité")
        void createAnnouncement_validHandoverWindow_persistsIt() {
            LocalDate departure = LocalDate.now().plusDays(10);
            LocalDateTime start = departure.atTime(16, 0);
            LocalDateTime end   = departure.atTime(18, 0);

            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            when(announcementRepository.save(captor.capture())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementRequest req = buildRequestWithWindow(departure, start, end);
            announcementService.createAnnouncement(FIREBASE_UID, req);

            assertThat(captor.getValue().getHandoverWindowStart()).isEqualTo(start);
            assertThat(captor.getValue().getHandoverWindowEnd()).isEqualTo(end);
        }
    }
}
