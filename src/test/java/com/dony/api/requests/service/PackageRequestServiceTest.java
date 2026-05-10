package com.dony.api.requests.service;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.requests.RequestsConfig;
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
