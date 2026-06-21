package com.dony.api.requests.service;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.StorageService;
import com.dony.api.favorites.FavoriteRepository;
import com.dony.api.favorites.FavoriteTargetType;
import com.dony.api.matching.TransportMode;
import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.requests.RequestsConfig;
import com.dony.api.requests.dto.PackageRequestCompleteDetailsRequest;
import com.dony.api.requests.dto.PackageRequestCreateRequest;
import com.dony.api.requests.entity.*;
import com.dony.api.requests.event.PackageRequestCreatedEvent;
import com.dony.api.requests.repository.NegotiationThreadRepository;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PackageRequestServiceTest {

    @Mock private PackageRequestRepository repository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private RequestsConfig config;
    @Mock private NegotiationThreadRepository threadRepository;
    @Mock private com.dony.api.city.CityRepository cityRepository;
    @Mock private com.dony.api.payments.cash.CommissionProperties commissionProperties;
    @Mock private StorageService storageService;
    @Mock private PackageRequestPhotoService photoService;
    @Mock private FavoriteRepository favoriteRepository;
    @InjectMocks private PackageRequestService service;

    private UserEntity sender;
    private final UUID SENDER_ID = UUID.randomUUID();

    // ─── Helpers ────────────────────────────────────────────────────────────────

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

    @BeforeEach
    void setup() {
        sender = new UserEntity();
        setId(sender, SENDER_ID);
        sender.setKycStatus(KycStatus.VERIFIED);
        // Default commission rate = 12% — lenient because only create-related tests use it
        lenient().when(commissionProperties.rate()).thenReturn(new BigDecimal("0.12"));
        // Pass-through for presigned avatar URLs
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        // Aucune photo par défaut (les mappers appellent activePhotos)
        lenient().when(photoService.activePhotos(any())).thenReturn(List.of());
    }

    // ========== Task 12: create() tests ==========

    @Nested @DisplayName("create() — happy path")
    class CreateHappyPath {
        @Test @DisplayName("création valide → persist + event PackageRequestCreatedEvent + audit log")
        void create_valid_persistsAndPublishesEvent() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> {
                PackageRequestEntity e = inv.getArgument(0);
                setId(e, UUID.randomUUID());
                return e;
            });

            PackageRequestCreateRequest req = validRequest();
            var response = service.create(SENDER_ID, req);

            assertThat(response.status()).isEqualTo(PackageRequestStatus.OPEN);
            assertThat(response.departureCity()).isEqualTo("Paris");
            verify(eventPublisher).publishEvent(any(PackageRequestCreatedEvent.class));
            verify(auditService).log(eq("PACKAGE_REQUEST"), any(UUID.class), eq("CREATED"), eq(SENDER_ID), anyMap());
        }

        @Test @DisplayName("création → attache les photoKeys via replacePhotos(reqId, sender, keys)")
        void create_attachesPhotoKeys() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            UUID reqId = UUID.randomUUID();
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> {
                PackageRequestEntity e = inv.getArgument(0);
                setId(e, reqId);
                return e;
            });

            List<String> keys = List.of("package_requests/" + SENDER_ID + "/1.jpg",
                                        "package_requests/" + SENDER_ID + "/2.jpg");
            var req = new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(5), 2,
                new BigDecimal("5"), "vetements", null, new BigDecimal("28.00"),
                null, null, null, true, EnumSet.of(PaymentMethod.STRIPE), keys);

            service.create(SENDER_ID, req);

            verify(photoService).replacePhotos(reqId, SENDER_ID, keys);
        }
    }

    @Nested @DisplayName("create() — validation errors")
    class CreateValidationErrors {
        @Test @DisplayName("KYC non vérifié → 403")
        void create_kycNotVerified_throws403() {
            sender.setKycStatus(KycStatus.PENDING);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            assertThatThrownBy(() -> service.create(SENDER_ID, validRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("kyc/not-verified");
        }

        @Test @DisplayName("departure == arrival → 422")
        void create_sameCorridor_throws422() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            PackageRequestCreateRequest req = new PackageRequestCreateRequest(
                "Paris", "Paris",
                LocalDate.now().plusDays(7), 2,
                new BigDecimal("5"), "vetements",
                null, null, null, null, null,
                true, EnumSet.of(PaymentMethod.STRIPE)
            , List.of());

            assertThatThrownBy(() -> service.create(SENDER_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid-corridor");
        }

        @Test @DisplayName("limite atteinte (10 requests OPEN) → 409")
        void create_atOpenLimit_throws409() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(10L);

            assertThatThrownBy(() -> service.create(SENDER_ID, validRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("max-open-reached");
        }

        @Test @DisplayName("desired_date > 90j → 422")
        void create_desiredDateTooFar_throws422() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            PackageRequestCreateRequest req = new PackageRequestCreateRequest(
                "Paris", "Dakar",
                LocalDate.now().plusDays(95), 2,
                new BigDecimal("5"), "vetements",
                null, null, null, null, null,
                true, EnumSet.of(PaymentMethod.STRIPE)
            , List.of());

            assertThatThrownBy(() -> service.create(SENDER_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("date-too-far");
        }
    }

    // ========== Task 5: createAndReturnEntity() — derived size, forced PLANE, gross→net, negotiable ==========

    @Nested @DisplayName("createAndReturnEntity() — avion forcé, taille dérivée, gross→net, négociable")
    class CreateAndReturnEntityTests {

        @Test @DisplayName("23kg → LARGE, transport=PLANE, net=gross/1.12, negotiable propagé")
        void create_derivesSize_forcesAvion_storesNetFromGross() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

            var req = new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(5), 2,
                new BigDecimal("23"), "Médicaments", "desc",
                new BigDecimal("39.20"), null, null, null,
                true, EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH), List.of());

            PackageRequestEntity saved = service.createAndReturnEntity(SENDER_ID, req);

            assertThat(saved.getParcelSize()).isEqualTo(ParcelSize.LARGE);   // 23 kg → LARGE
            assertThat(saved.getTransportMode()).isEqualTo(TransportMode.PLANE);
            assertThat(saved.getTargetPriceEur()).isEqualByComparingTo("35.00"); // 39.20 / 1.12
            assertThat(saved.isNegotiable()).isTrue();
        }

        @Test @DisplayName("budget null + non négociable → 422 target-price-required-firm")
        void create_firmPrice_requiresBudget() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            var req = new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(5), 2,
                new BigDecimal("6"), "Médicaments", null,
                null /* pas de budget */, null, null, null,
                false /* non négociable */, EnumSet.of(PaymentMethod.STRIPE), List.of());

            assertThatThrownBy(() -> service.createAndReturnEntity(SENDER_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("target-price-required-firm");
        }
    }

    // ========== Task 13: getById() / findMine() / cancel() / completeDetails() tests ==========

    @Nested @DisplayName("getById() — ownership")
    class GetByIdTests {

        @Test @DisplayName("sender owner → OK")
        void getById_owner_returnsResponse() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);

            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

            var resp = service.getById(SENDER_ID, entity.getId());
            assertThat(resp.id()).isEqualTo(entity.getId());
        }

        @Test @DisplayName("photos non vides → photos[] présignées + photoUrl = 1ère")
        void getById_withPhotos_exposesPhotosAndDerivesPhotoUrl() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(photoService.activePhotos(entity.getId())).thenReturn(List.of(
                new com.dony.api.requests.dto.PackageRequestPhotoResponse(UUID.randomUUID(), "package_requests/s/1.jpg", "https://signed/1"),
                new com.dony.api.requests.dto.PackageRequestPhotoResponse(UUID.randomUUID(), "package_requests/s/2.jpg", "https://signed/2")));

            var resp = service.getById(SENDER_ID, entity.getId());

            assertThat(resp.photos()).hasSize(2);
            assertThat(resp.photoUrl()).isEqualTo("https://signed/1");
        }

        @Test @DisplayName("voyageur avec offre active → viewerThreadId + statut exposés")
        void getById_travelerWithActiveThread_exposesViewerThread() {
            UUID traveler = UUID.randomUUID();
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            UUID threadId = UUID.randomUUID();
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            setId(thread, threadId);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            when(threadRepository.findActiveByPackageRequestIdAndTravelerId(entity.getId(), traveler))
                .thenReturn(Optional.of(thread));

            var resp = service.getById(traveler, entity.getId());

            assertThat(resp.viewerThreadId()).isEqualTo(threadId);
            assertThat(resp.viewerThreadStatus()).isEqualTo("OPEN");
        }

        @Test @DisplayName("propriétaire → pas de viewerThreadId")
        void getById_owner_noViewerThread() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

            var resp = service.getById(SENDER_ID, entity.getId());

            assertThat(resp.viewerThreadId()).isNull();
        }

        @Test @DisplayName("non-participant, demande OPEN → OK (consultable publiquement)")
        void getById_nonParticipant_openRequest_returnsResponse() {
            UUID OTHER = UUID.randomUUID();
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(threadRepository.existsByPackageRequestIdAndTravelerId(entity.getId(), OTHER))
                .thenReturn(false);

            var resp = service.getById(OTHER, entity.getId());
            assertThat(resp.id()).isEqualTo(entity.getId());
        }

        @Test @DisplayName("non-participant, demande NEGOTIATING → OK (consultable publiquement)")
        void getById_nonParticipant_negotiatingRequest_returnsResponse() {
            UUID OTHER = UUID.randomUUID();
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.NEGOTIATING);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(threadRepository.existsByPackageRequestIdAndTravelerId(entity.getId(), OTHER))
                .thenReturn(false);

            var resp = service.getById(OTHER, entity.getId());
            assertThat(resp.id()).isEqualTo(entity.getId());
        }

        @Test @DisplayName("non-participant, demande ACCEPTED (non listée) → 403")
        void getById_nonParticipant_acceptedRequest_throws403() {
            UUID OTHER = UUID.randomUUID();
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.ACCEPTED);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(threadRepository.existsByPackageRequestIdAndTravelerId(entity.getId(), OTHER))
                .thenReturn(false);

            assertThatThrownBy(() -> service.getById(OTHER, entity.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("forbidden");
        }

        @Test @DisplayName("participant d'un thread, demande ACCEPTED → OK")
        void getById_threadParticipant_acceptedRequest_returnsResponse() {
            UUID OTHER = UUID.randomUUID();
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.ACCEPTED);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(threadRepository.existsByPackageRequestIdAndTravelerId(entity.getId(), OTHER))
                .thenReturn(true);

            var resp = service.getById(OTHER, entity.getId());
            assertThat(resp.id()).isEqualTo(entity.getId());
        }
    }

    @Nested @DisplayName("update() — édition tant qu'aucun accord")
    class UpdateTests {

        @Test @DisplayName("OPEN → met à jour les champs, reste OPEN, audit UPDATED")
        void update_open_updatesAndStaysOpen() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(threadRepository.findByPackageRequestId(entity.getId())).thenReturn(List.of());
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            var req = new PackageRequestCreateRequest(
                "Lyon", "Bamako", LocalDate.now().plusDays(10), 3,
                new BigDecimal("8"), "electronique", "desc",
                new BigDecimal("56.00"), null, "7e", "ACI 2000",
                true, EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH), List.of());

            var resp = service.update(SENDER_ID, entity.getId(), req);

            assertThat(entity.getDepartureCity()).isEqualTo("Lyon");
            assertThat(entity.getArrivalCity()).isEqualTo("Bamako");
            assertThat(entity.getWeightKg()).isEqualByComparingTo("8");
            assertThat(entity.getStatus()).isEqualTo(PackageRequestStatus.OPEN);
            // net = 56 / 1.12 = 50.00
            assertThat(entity.getTargetPriceEur()).isEqualByComparingTo("50.00");
            assertThat(resp.id()).isEqualTo(entity.getId());
            verify(repository).save(entity);
            verify(auditService).log(eq("PACKAGE_REQUEST"), eq(entity.getId()),
                eq("UPDATED"), eq(SENDER_ID), any());
        }

        @Test @DisplayName("NEGOTIATING → rejette les offres OPEN et repasse OPEN")
        void update_negotiating_rejectsOpenThreads() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.NEGOTIATING);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setStatus(NegotiationThreadStatus.OPEN);
            when(threadRepository.findByPackageRequestId(entity.getId()))
                .thenReturn(List.of(thread));
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            service.update(SENDER_ID, entity.getId(), validRequest());

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AUTO_REJECTED);
            assertThat(entity.getStatus()).isEqualTo(PackageRequestStatus.OPEN);
            verify(threadRepository).save(thread);
        }

        @Test @DisplayName("ACCEPTED → 409 not-editable")
        void update_accepted_throws409() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.ACCEPTED);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.update(SENDER_ID, entity.getId(), validRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-editable");
        }

        @Test @DisplayName("autre que le propriétaire → 403")
        void update_notOwner_throws403() {
            UUID other = UUID.randomUUID();
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.update(other, entity.getId(), validRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("forbidden");
        }

        @Test @DisplayName("prix ferme sans budget → 422")
        void update_firmWithoutBudget_throws422() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

            var req = new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(7), 2,
                new BigDecimal("5"), "vetements", null, null, null, null, null,
                false, EnumSet.of(PaymentMethod.STRIPE), List.of());

            assertThatThrownBy(() -> service.update(SENDER_ID, entity.getId(), req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("target-price-required-firm");
        }

        @Test @DisplayName("corridor invalide (mêmes villes) → 422")
        void update_sameCorridor_throws422() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

            var req = new PackageRequestCreateRequest(
                "Paris", "Paris", LocalDate.now().plusDays(7), 2,
                new BigDecimal("5"), "vetements", null, new BigDecimal("28.00"),
                null, null, null, true, EnumSet.of(PaymentMethod.STRIPE), List.of());

            assertThatThrownBy(() -> service.update(SENDER_ID, entity.getId(), req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid-corridor");
        }
    }

    @Nested @DisplayName("cancel() — soft delete + cascade threads")
    class CancelTests {
        @Test @DisplayName("status OPEN → soft delete + threads auto-rejected")
        void cancel_open_softDeletesAndCancelsThreads() {
            UUID reqId = UUID.randomUUID();
            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, reqId);
            entity.setSenderId(SENDER_ID);
            entity.setStatus(PackageRequestStatus.OPEN);
            when(repository.findById(reqId)).thenReturn(Optional.of(entity));
            when(threadRepository.findByPackageRequestId(reqId)).thenReturn(List.of());

            service.cancel(SENDER_ID, reqId);

            assertThat(entity.getStatus()).isEqualTo(PackageRequestStatus.CANCELLED);
            verify(repository).save(entity);
        }

        @Test @DisplayName("status ACCEPTED → 409")
        void cancel_accepted_throws409() {
            UUID reqId = UUID.randomUUID();
            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, reqId);
            entity.setSenderId(SENDER_ID);
            entity.setStatus(PackageRequestStatus.ACCEPTED);
            when(repository.findById(reqId)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.cancel(SENDER_ID, reqId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already-accepted");
        }
    }

    @Nested @DisplayName("completeDetails() — post-acceptation")
    class CompleteDetailsTests {
        @Test @DisplayName("status ACCEPTED → renseigne recipient (name + phone + city)")
        void completeDetails_accepted_persists() {
            UUID reqId = UUID.randomUUID();
            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, reqId);
            entity.setSenderId(SENDER_ID);
            entity.setStatus(PackageRequestStatus.ACCEPTED);
            when(repository.findById(reqId)).thenReturn(Optional.of(entity));
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            var req = new PackageRequestCompleteDetailsRequest(
                "Marie", "+221771234567", "Dakar", new BigDecimal("120.00")
            );

            service.completeDetails(SENDER_ID, reqId, req, "203.0.113.5");

            assertThat(entity.getRecipientName()).isEqualTo("Marie");
            assertThat(entity.getRecipientPhone()).isEqualTo("+221771234567");
            assertThat(entity.getRecipientCity()).isEqualTo("Dakar");
            assertThat(entity.getDeclaredValueEur()).isEqualByComparingTo("120.00");
            // The entity had no disclaimerSignedAt (bare entity), so the defensive
            // branch signs it now using the client IP.
            assertThat(entity.getDisclaimerSignedAt()).isNotNull();
            assertThat(entity.getDisclaimerSignedIp()).isEqualTo("203.0.113.5");
        }

        @Test @DisplayName("status ACCEPTED + city null → succès, disclaimer déjà signé conservé")
        void completeDetails_accepted_nullCity_persists() {
            UUID reqId = UUID.randomUUID();
            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, reqId);
            entity.setSenderId(SENDER_ID);
            entity.setStatus(PackageRequestStatus.ACCEPTED);
            var signedAt = java.time.LocalDateTime.now().minusDays(1);
            entity.setDisclaimerSignedAt(signedAt); // signed at creation
            when(repository.findById(reqId)).thenReturn(Optional.of(entity));
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            var req = new PackageRequestCompleteDetailsRequest(
                "Fatou Diop", "+221771234567", null, new BigDecimal("80.00")
            );

            service.completeDetails(SENDER_ID, reqId, req, "203.0.113.5");

            assertThat(entity.getRecipientName()).isEqualTo("Fatou Diop");
            assertThat(entity.getRecipientPhone()).isEqualTo("+221771234567");
            assertThat(entity.getRecipientCity()).isNull();
            // disclaimer was already signed at creation → not overwritten, no IP set
            assertThat(entity.getDisclaimerSignedAt()).isEqualTo(signedAt);
            assertThat(entity.getDisclaimerSignedIp()).isNull();
        }

        @Test @DisplayName("status OPEN → 409 not-yet-accepted")
        void completeDetails_open_throws409() {
            UUID reqId = UUID.randomUUID();
            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, reqId);
            entity.setSenderId(SENDER_ID);
            entity.setStatus(PackageRequestStatus.OPEN);
            when(repository.findById(reqId)).thenReturn(Optional.of(entity));

            var req = new PackageRequestCompleteDetailsRequest(
                "Z", "+221771234567", "Dakar", new BigDecimal("50.00")
            );

            assertThatThrownBy(() -> service.completeDetails(SENDER_ID, reqId, req, "1.2.3.4"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-yet-accepted");
        }

        @Test @DisplayName("thread AWAITING_PAYMENT (avant paiement) → succès même si request pas ACCEPTED")
        void completeDetails_awaitingPaymentThread_persists() {
            UUID reqId = UUID.randomUUID();
            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, reqId);
            entity.setSenderId(SENDER_ID);
            entity.setStatus(PackageRequestStatus.NEGOTIATING); // pas encore ACCEPTED
            when(repository.findById(reqId)).thenReturn(Optional.of(entity));
            when(repository.save(any(PackageRequestEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            when(threadRepository.findByPackageRequestId(reqId))
                .thenReturn(List.of(thread));

            var req = new PackageRequestCompleteDetailsRequest(
                "Awa", "+221770000000", "Abobo", new BigDecimal("99.00")
            );

            service.completeDetails(SENDER_ID, reqId, req, "203.0.113.9");

            assertThat(entity.getRecipientName()).isEqualTo("Awa");
            assertThat(entity.getRecipientPhone()).isEqualTo("+221770000000");
            assertThat(entity.getRecipientCity()).isEqualTo("Abobo");
        }
    }

    @Nested @DisplayName("searchNearMe() — geo proximity filter")
    class SearchNearMeTests {

        @Test @DisplayName("filtre + tri par distance asc, exclut hors radius et coords inconnues")
        void searchNearMe_filtersAndSorts() {
            // 3 demandes : Paris (ref), Lyon, Marseille — viewer à Paris, radius 600 km → garde Paris (0km) + Lyon (~390km), exclut Marseille (~660km)
            PackageRequestEntity paris = buildEntity(UUID.randomUUID(), PackageRequestStatus.OPEN);
            paris.setDepartureCity("Paris");
            PackageRequestEntity lyon = buildEntity(UUID.randomUUID(), PackageRequestStatus.OPEN);
            lyon.setDepartureCity("Lyon");
            PackageRequestEntity marseille = buildEntity(UUID.randomUUID(), PackageRequestStatus.OPEN);
            marseille.setDepartureCity("Marseille");

            var page = new org.springframework.data.domain.PageImpl<>(List.of(marseille, lyon, paris));
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);

            com.dony.api.city.CityEntity cityParis = cityWith(new BigDecimal("48.8566"), new BigDecimal("2.3522"));
            com.dony.api.city.CityEntity cityLyon = cityWith(new BigDecimal("45.7640"), new BigDecimal("4.8357"));
            com.dony.api.city.CityEntity cityMarseille = cityWith(new BigDecimal("43.2965"), new BigDecimal("5.3698"));
            when(cityRepository.findFirstByNameIgnoreCase("Paris")).thenReturn(Optional.of(cityParis));
            when(cityRepository.findFirstByNameIgnoreCase("Lyon")).thenReturn(Optional.of(cityLyon));
            when(cityRepository.findFirstByNameIgnoreCase("Marseille")).thenReturn(Optional.of(cityMarseille));
            when(cityRepository.findFirstByNameIgnoreCase("Dakar")).thenReturn(Optional.empty());

            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.searchNearMe(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                new BigDecimal("48.8566"), new BigDecimal("2.3522"),
                600.0,
                UUID.randomUUID()
            );

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent().get(0).departureCity()).isEqualTo("Paris");
            assertThat(result.getContent().get(1).departureCity()).isEqualTo("Lyon");
        }

        @Test @DisplayName("toutes hors radius → page vide")
        void searchNearMe_allOutOfRadius_returnsEmpty() {
            PackageRequestEntity lyon = buildEntity(UUID.randomUUID(), PackageRequestStatus.OPEN);
            lyon.setDepartureCity("Lyon");
            var page = new org.springframework.data.domain.PageImpl<>(List.of(lyon));
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);
            when(cityRepository.findFirstByNameIgnoreCase("Lyon"))
                .thenReturn(Optional.of(cityWith(new BigDecimal("45.7640"), new BigDecimal("4.8357"))));
            when(cityRepository.findFirstByNameIgnoreCase("Dakar")).thenReturn(Optional.empty());
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.searchNearMe(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                new BigDecimal("48.8566"), new BigDecimal("2.3522"),
                10.0,
                UUID.randomUUID()
            );
            assertThat(result.getContent()).isEmpty();
        }

        private com.dony.api.city.CityEntity cityWith(BigDecimal lat, BigDecimal lng) {
            com.dony.api.city.CityEntity c = new com.dony.api.city.CityEntity();
            c.setLatitude(lat);
            c.setLongitude(lng);
            return c;
        }
    }

    @Nested @DisplayName("search() — toSearchResponse propagation")
    class SearchTests {
        @Test @DisplayName("sender.ratingCount est propagé dans sender.totalRatings du SearchResponse")
        void search_propagatesSenderRatingCount() {
            sender.setRatingCount(7);
            sender.setAverageRating(new java.math.BigDecimal("4.30"));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(cityRepository.findFirstByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.search(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                SENDER_ID
            );

            var sp = result.getContent().get(0).sender();
            assertThat(sp.totalRatings()).isEqualTo(7);
            assertThat(sp.averageRating()).isEqualTo(4.30);
            assertThat(sp.kycVerified()).isTrue();
            // negotiable is propagated from the entity (default true)
            assertThat(result.getContent().get(0).negotiable()).isTrue();
        }

        @Test @DisplayName("negotiable=false (demande à prix ferme) est propagé dans le SearchResponse")
        void search_propagatesFirmPriceNegotiableFalse() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            entity.setNegotiable(false);
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(cityRepository.findFirstByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.search(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                SENDER_ID
            );

            assertThat(result.getContent().get(0).negotiable()).isFalse();
        }

        @Test @DisplayName("acceptedPaymentMethods est propagé dans le SearchResponse")
        void search_propagatesAcceptedPaymentMethods() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            entity.setAcceptedPaymentMethods(java.util.Set.of(
                com.dony.api.payments.cash.PaymentMethod.STRIPE,
                com.dony.api.payments.cash.PaymentMethod.CASH));
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(cityRepository.findFirstByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.search(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                SENDER_ID
            );

            assertThat(result.getContent().get(0).acceptedPaymentMethods())
                .containsExactlyInAnyOrder(
                    com.dony.api.payments.cash.PaymentMethod.STRIPE,
                    com.dony.api.payments.cash.PaymentMethod.CASH);
        }
    }

    @Nested @DisplayName("search() — isFavorite flag")
    class SearchIsFavoriteTests {

        private PackageRequestEntity prepareEntity() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findAll(
                    org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<PackageRequestEntity>>any(),
                    org.mockito.ArgumentMatchers.<org.springframework.data.domain.Pageable>any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(cityRepository.findFirstByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
            return entity;
        }

        @Test @DisplayName("voyageur authentifié — demande en favori → isFavorite=true")
        void search_travelerWithFavorite_returnsTrueFlag() {
            PackageRequestEntity entity = prepareEntity();
            UUID travelerId = UUID.randomUUID();
            when(favoriteRepository.findTargetIds(travelerId, FavoriteTargetType.PACKAGE_REQUEST))
                .thenReturn(List.of(entity.getId()));

            var result = service.search(null,
                org.springframework.data.domain.PageRequest.of(0, 20), travelerId);

            assertThat(result.getContent().get(0).isFavorite()).isTrue();
        }

        @Test @DisplayName("voyageur authentifié — demande non mise en favori → isFavorite=false")
        void search_travelerWithoutFavorite_returnsFalseFlag() {
            prepareEntity();
            UUID travelerId = UUID.randomUUID();
            when(favoriteRepository.findTargetIds(travelerId, FavoriteTargetType.PACKAGE_REQUEST))
                .thenReturn(List.of());

            var result = service.search(null,
                org.springframework.data.domain.PageRequest.of(0, 20), travelerId);

            assertThat(result.getContent().get(0).isFavorite()).isFalse();
        }

        @Test @DisplayName("appelant anonyme (callerId=null) → isFavorite=false, findTargetIds jamais appelé")
        void search_anonymousCaller_returnsFalseFlagWithoutDbCall() {
            prepareEntity();

            var result = service.search(null,
                org.springframework.data.domain.PageRequest.of(0, 20), null);

            assertThat(result.getContent().get(0).isFavorite()).isFalse();
            verify(favoriteRepository, never()).findTargetIds(any(), any());
        }
    }

    @Nested @DisplayName("findMine() — own requests pagination")
    class FindMineTests {
        @Test @DisplayName("returns paginated responses for sender")
        void findMine_returnsPage() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);

            var page = new org.springframework.data.domain.PageImpl<>(List.of(entity));
            when(repository.findBySenderIdOrderByCreatedAtDesc(eq(SENDER_ID), any()))
                .thenReturn(page);

            var result = service.findMine(SENDER_ID, org.springframework.data.domain.PageRequest.of(0, 20));
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).senderId()).isEqualTo(SENDER_ID);
        }

    }

    // ─── Shared helpers ─────────────────────────────────────────────────────────

    private PackageRequestEntity buildEntity(UUID senderId, PackageRequestStatus status) {
        PackageRequestEntity entity = new PackageRequestEntity();
        setId(entity, UUID.randomUUID());
        entity.setSenderId(senderId);
        entity.setDepartureCity("Paris");
        entity.setArrivalCity("Dakar");
        entity.setDesiredDate(LocalDate.now().plusDays(7));
        entity.setDateToleranceDays((short) 2);
        entity.setWeightKg(new BigDecimal("5"));
        entity.setParcelSize(ParcelSize.SMALL);
        entity.setTransportMode(com.dony.api.matching.TransportMode.PLANE);
        entity.setContentCategory("vetements");
        entity.setStatus(status);
        return entity;
    }

    private PackageRequestCreateRequest validRequest() {
        return new PackageRequestCreateRequest(
            "Paris", "Dakar",
            LocalDate.now().plusDays(7), 2,
            new BigDecimal("5"), "vetements",
            "Cadeau pour ma mère", new BigDecimal("28.00"), null,
            "10e arr", "Plateau",
            true, EnumSet.of(PaymentMethod.STRIPE)
        , List.of());
    }

    // ========== AvatarUrl in SenderPublicProfile ==========

    @Nested @DisplayName("search() — SenderPublicProfile.avatarUrl")
    class SenderPublicProfileAvatarTests {

        private PackageRequestEntity buildEntity() {
            PackageRequestEntity e = new PackageRequestEntity();
            setId(e, UUID.randomUUID());
            e.setSenderId(SENDER_ID);
            e.setDepartureCity("Paris");
            e.setArrivalCity("Dakar");
            e.setDesiredDate(LocalDate.now().plusDays(5));
            e.setWeightKg(new BigDecimal("5"));
            e.setStatus(PackageRequestStatus.OPEN);
            e.setNegotiable(true);
            e.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
            return e;
        }

        @Test @DisplayName("sender avec avatarUrl → SenderPublicProfile.avatarUrl propagé")
        void search_senderAvatarUrl_isMapped() {
            sender.setAvatarUrl("https://cdn.example.com/sender.jpg");
            PackageRequestEntity entity = buildEntity();

            when(repository.findAll(
                    org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<PackageRequestEntity>>any(),
                    org.mockito.ArgumentMatchers.<org.springframework.data.domain.Pageable>any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var page = service.search(null, org.springframework.data.domain.PageRequest.of(0, 10), SENDER_ID);

            var result = page.getContent().get(0);
            assertThat(result.sender().avatarUrl()).isEqualTo("https://cdn.example.com/sender.jpg");
        }

        @Test @DisplayName("sender sans avatarUrl → SenderPublicProfile.avatarUrl null")
        void search_senderNoAvatarUrl_null() {
            // sender.avatarUrl is null by default
            PackageRequestEntity entity = buildEntity();

            when(repository.findAll(
                    org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<PackageRequestEntity>>any(),
                    org.mockito.ArgumentMatchers.<org.springframework.data.domain.Pageable>any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var page = service.search(null, org.springframework.data.domain.PageRequest.of(0, 10), SENDER_ID);

            var result = page.getContent().get(0);
            assertThat(result.sender().avatarUrl()).isNull();
        }

        @Test @DisplayName("sender introuvable → SenderPublicProfile.avatarUrl null")
        void search_senderNotFound_avatarUrlNull() {
            PackageRequestEntity entity = buildEntity();

            when(repository.findAll(
                    org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<PackageRequestEntity>>any(),
                    org.mockito.ArgumentMatchers.<org.springframework.data.domain.Pageable>any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var page = service.search(null, org.springframework.data.domain.PageRequest.of(0, 10), SENDER_ID);

            var result = page.getContent().get(0);
            assertThat(result.sender().avatarUrl()).isNull();
        }
    }
}
