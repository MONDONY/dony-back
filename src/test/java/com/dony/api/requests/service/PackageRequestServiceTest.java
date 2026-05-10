package com.dony.api.requests.service;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
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
                new BigDecimal("5"), ParcelSize.SMALL, "vetements",
                null, null, null, null, null
            );

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
                new BigDecimal("5"), ParcelSize.SMALL, "vetements",
                null, null, null, null, null
            );

            assertThatThrownBy(() -> service.create(SENDER_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("date-too-far");
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

        @Test @DisplayName("autre sender, pas de thread → 403")
        void getById_otherCallerNoThread_throws403() {
            UUID OTHER = UUID.randomUUID();
            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, UUID.randomUUID());
            entity.setSenderId(SENDER_ID);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(threadRepository.findByPackageRequestIdAndTravelerId(entity.getId(), OTHER))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(OTHER, entity.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("forbidden");
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
        @Test @DisplayName("status ACCEPTED → renseigne adresses + recipient + disclaimer")
        void completeDetails_accepted_persists() {
            UUID reqId = UUID.randomUUID();
            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, reqId);
            entity.setSenderId(SENDER_ID);
            entity.setStatus(PackageRequestStatus.ACCEPTED);
            when(repository.findById(reqId)).thenReturn(Optional.of(entity));
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            var req = new PackageRequestCompleteDetailsRequest(
                "12 rue de Paris", new BigDecimal("48.85"), new BigDecimal("2.35"),
                "5 rue de Dakar", new BigDecimal("14.69"), new BigDecimal("-17.44"),
                "Marie", "+221771234567", new BigDecimal("100"), true
            );

            service.completeDetails(SENDER_ID, reqId, req, "203.0.113.5");

            assertThat(entity.getPickupAddressLabel()).isEqualTo("12 rue de Paris");
            assertThat(entity.getRecipientName()).isEqualTo("Marie");
            assertThat(entity.getDeclaredValueEur()).isEqualByComparingTo(new BigDecimal("100"));
            assertThat(entity.getDisclaimerSignedAt()).isNotNull();
            assertThat(entity.getDisclaimerSignedIp()).isEqualTo("203.0.113.5");
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
                "X", new BigDecimal("1"), new BigDecimal("1"),
                "Y", new BigDecimal("1"), new BigDecimal("1"),
                "Z", "+221771234567", new BigDecimal("100"), true
            );

            assertThatThrownBy(() -> service.completeDetails(SENDER_ID, reqId, req, "1.2.3.4"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-yet-accepted");
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
        entity.setContentCategory("vetements");
        entity.setStatus(status);
        return entity;
    }

    private PackageRequestCreateRequest validRequest() {
        return new PackageRequestCreateRequest(
            "Paris", "Dakar",
            LocalDate.now().plusDays(7), 2,
            new BigDecimal("5"), ParcelSize.SMALL, "vetements",
            "Cadeau pour ma mère", new BigDecimal("25"), null,
            "10e arr", "Plateau"
        );
    }
}
