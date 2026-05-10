package com.dony.api.requests.service;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.requests.RequestsConfig;
import com.dony.api.requests.dto.NegotiationStartRequest;
import com.dony.api.requests.entity.*;
import com.dony.api.requests.event.NegotiationStartedEvent;
import com.dony.api.requests.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NegotiationServiceTest {

    @Mock private PackageRequestRepository requestRepo;
    @Mock private NegotiationThreadRepository threadRepo;
    @Mock private NegotiationMessageRepository messageRepo;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private RequestsConfig config;

    @InjectMocks private NegotiationService service;

    private final UUID SENDER_ID = UUID.randomUUID();
    private final UUID TRAVELER_ID = UUID.randomUUID();
    private final UUID REQUEST_ID = UUID.randomUUID();

    private PackageRequestEntity request;
    private UserEntity traveler;

    @BeforeEach
    void setup() {
        traveler = new UserEntity();
        traveler.setKycStatus(KycStatus.VERIFIED);
        // Set id via reflection (BaseEntity has no public setId)
        try {
            var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(traveler, TRAVELER_ID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        request = new PackageRequestEntity();
        request.setSenderId(SENDER_ID);
        request.setStatus(PackageRequestStatus.OPEN);
        try {
            var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(request, REQUEST_ID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("start() — happy path")
    class StartHappyPath {
        @Test
        @DisplayName("traveler ouvre thread → status OPEN, rounds=1, message PROPOSAL, event")
        void start_valid_createsThreadWithProposalMessage() {
            when(config.maxOpenThreadsPerTraveler()).thenReturn(5);
            when(config.threadsPerMinuteRateLimit()).thenReturn(1);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
                .thenReturn(Optional.empty());
            when(threadRepo.countByTravelerIdAndStatus(eq(TRAVELER_ID), eq(NegotiationThreadStatus.OPEN)))
                .thenReturn(0L);
            when(threadRepo.countCreatedBy(eq(TRAVELER_ID), any())).thenReturn(0L);
            when(threadRepo.save(any())).thenAnswer(inv -> {
                NegotiationThreadEntity t = inv.getArgument(0);
                try {
                    var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(t, UUID.randomUUID());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return t;
            });

            var req = new NegotiationStartRequest(
                REQUEST_ID, new BigDecimal("30"),
                LocalDate.now().plusDays(5), new BigDecimal("10"),
                null, "Pas de problème"
            );
            var response = service.start(TRAVELER_ID, req);

            assertThat(response.status()).isEqualTo(NegotiationThreadStatus.OPEN);
            assertThat(response.roundsCount()).isEqualTo(1);
            assertThat(response.currentPriceEur()).isEqualByComparingTo("30");
            verify(messageRepo).save(argThat(m -> m.getKind() == NegotiationMessageKind.PROPOSAL));
            verify(eventPublisher).publishEvent(any(NegotiationStartedEvent.class));
        }
    }

    @Nested
    @DisplayName("start() — validation errors")
    class StartValidationErrors {
        @Test
        @DisplayName("traveler bid sa propre request → 403")
        void start_ownRequest_throws403() {
            request.setSenderId(TRAVELER_ID);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.start(TRAVELER_ID, validStartReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot-bid-own-request");
        }

        @Test
        @DisplayName("KYC non vérifié → 403")
        void start_kycNotVerified_throws403() {
            traveler.setKycStatus(KycStatus.PENDING);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> service.start(TRAVELER_ID, validStartReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("kyc/not-verified");
        }

        @Test
        @DisplayName("thread existant → 409 duplicate")
        void start_duplicate_throws409() {
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
                .thenReturn(Optional.of(new NegotiationThreadEntity()));

            assertThatThrownBy(() -> service.start(TRAVELER_ID, validStartReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("duplicate-thread");
        }

        @Test
        @DisplayName("request status EXPIRED → 410")
        void start_requestExpired_throws410() {
            request.setStatus(PackageRequestStatus.EXPIRED);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.start(TRAVELER_ID, validStartReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("request/expired");
        }

        @Test
        @DisplayName("rate limit dépassé → 429")
        void start_rateLimitExceeded_throws429() {
            when(config.maxOpenThreadsPerTraveler()).thenReturn(5);
            when(config.threadsPerMinuteRateLimit()).thenReturn(1);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
                .thenReturn(Optional.empty());
            when(threadRepo.countByTravelerIdAndStatus(eq(TRAVELER_ID), eq(NegotiationThreadStatus.OPEN)))
                .thenReturn(0L);
            when(threadRepo.countCreatedBy(eq(TRAVELER_ID), any())).thenReturn(2L);

            assertThatThrownBy(() -> service.start(TRAVELER_ID, validStartReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("rate-limit");
        }

        @Test
        @DisplayName("max-open-threads atteint → 409")
        void start_atMaxOpenThreads_throws409() {
            when(config.maxOpenThreadsPerTraveler()).thenReturn(5);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
                .thenReturn(Optional.empty());
            when(threadRepo.countByTravelerIdAndStatus(eq(TRAVELER_ID), eq(NegotiationThreadStatus.OPEN)))
                .thenReturn(5L);

            assertThatThrownBy(() -> service.start(TRAVELER_ID, validStartReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("max-open-reached");
        }
    }

    private NegotiationStartRequest validStartReq() {
        return new NegotiationStartRequest(
            REQUEST_ID, new BigDecimal("30"),
            LocalDate.now().plusDays(5), new BigDecimal("10"),
            null, null
        );
    }

    @Nested
    @DisplayName("counter() — alternance + rounds")
    class CounterTests {
        private NegotiationThreadEntity thread;
        private final UUID THREAD_ID = UUID.randomUUID();

        @org.junit.jupiter.api.BeforeEach
        void setupThread() {
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        @DisplayName("sender counter après PROPOSAL traveler → OK, rounds++, message COUNTER")
        void counter_validAlternance_savesAndIncrementsRounds() {
            when(config.maxNegotiationRounds()).thenReturn(5);
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            // Last message was PROPOSAL by traveler
            var lastMsg = NegotiationMessageEntity.create(THREAD_ID, TRAVELER_ID,
                NegotiationMessageKind.PROPOSAL, new BigDecimal("30"), null);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(lastMsg));

            var req = new com.dony.api.requests.dto.NegotiationCounterRequest(
                new BigDecimal("25"), "Mon contre");
            var response = service.counter(SENDER_ID, THREAD_ID, req);

            assertThat(response.roundsCount()).isEqualTo(2);
            assertThat(response.currentPriceEur()).isEqualByComparingTo("25");
            verify(messageRepo).save(argThat(m -> m.getKind() == NegotiationMessageKind.COUNTER
                && m.getFromUserId().equals(SENDER_ID)));
            verify(eventPublisher).publishEvent(any(com.dony.api.requests.event.NegotiationCounterPostedEvent.class));
        }

        @Test
        @DisplayName("même partie 2 fois d'affilée → 409 not-your-turn")
        void counter_sameSideTwice_throws409() {
            when(config.maxNegotiationRounds()).thenReturn(5);
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            var lastMsg = NegotiationMessageEntity.create(THREAD_ID, SENDER_ID,
                NegotiationMessageKind.COUNTER, new BigDecimal("28"), null);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(lastMsg));

            var req = new com.dony.api.requests.dto.NegotiationCounterRequest(
                new BigDecimal("27"), null);

            assertThatThrownBy(() -> service.counter(SENDER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-your-turn");
        }

        @Test
        @DisplayName("rounds_count >= max → 409 max-rounds-reached")
        void counter_atMaxRounds_throws409() {
            when(config.maxNegotiationRounds()).thenReturn(5);
            thread.setRoundsCount((short) 5);
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            var req = new com.dony.api.requests.dto.NegotiationCounterRequest(
                new BigDecimal("25"), null);

            assertThatThrownBy(() -> service.counter(SENDER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("max-rounds-reached");
        }

        @Test
        @DisplayName("thread status REJECTED → 410 expired")
        void counter_threadNotOpen_throws410() {
            thread.setStatus(NegotiationThreadStatus.REJECTED);
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));

            var req = new com.dony.api.requests.dto.NegotiationCounterRequest(
                new BigDecimal("25"), null);

            assertThatThrownBy(() -> service.counter(SENDER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("thread/expired");
        }

        @Test
        @DisplayName("non-participant → 403")
        void counter_outsider_throws403() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            UUID OUTSIDER = UUID.randomUUID();
            var req = new com.dony.api.requests.dto.NegotiationCounterRequest(
                new BigDecimal("25"), null);

            assertThatThrownBy(() -> service.counter(OUTSIDER, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-thread-participant");
        }
    }

    @Nested
    @DisplayName("reject() — manual rejection")
    class RejectTests {
        private NegotiationThreadEntity thread;
        private final UUID THREAD_ID = UUID.randomUUID();

        @org.junit.jupiter.api.BeforeEach
        void setupThread() {
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        @DisplayName("traveler reject → thread REJECTED, message REJECT")
        void reject_byTraveler_marksRejected() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            var req = new com.dony.api.requests.dto.NegotiationRejectRequest("Trop cher pour moi");
            service.reject(TRAVELER_ID, THREAD_ID, req);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.REJECTED);
            verify(messageRepo).save(argThat(m -> m.getKind() == NegotiationMessageKind.REJECT
                && "Trop cher pour moi".equals(m.getBody())));
            verify(threadRepo).save(thread);
        }

        @Test
        @DisplayName("non-participant → 403")
        void reject_outsider_throws403() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            UUID OUTSIDER = UUID.randomUUID();
            var req = new com.dony.api.requests.dto.NegotiationRejectRequest("nope");

            assertThatThrownBy(() -> service.reject(OUTSIDER, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-thread-participant");
        }

        @Test
        @DisplayName("thread status REJECTED → 409 already-finalized")
        void reject_alreadyRejected_throws409() {
            thread.setStatus(NegotiationThreadStatus.REJECTED);
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));

            var req = new com.dony.api.requests.dto.NegotiationRejectRequest("dup");
            assertThatThrownBy(() -> service.reject(SENDER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already-finalized");
        }
    }

    @Nested
    @DisplayName("getById() / listMine()")
    class GetByIdAndListTests {
        @Test
        @DisplayName("getById — participant → response avec messages")
        void getById_participant_returnsThreadWithMessages() {
            UUID THREAD_ID = UUID.randomUUID();
            var thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(java.util.List.of());

            var resp = service.getById(TRAVELER_ID, THREAD_ID);
            assertThat(resp.id()).isEqualTo(THREAD_ID);
        }

        @Test
        @DisplayName("getById — non-participant → 403")
        void getById_outsider_throws403() {
            UUID THREAD_ID = UUID.randomUUID();
            var thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            UUID OUTSIDER = UUID.randomUUID();
            assertThatThrownBy(() -> service.getById(OUTSIDER, THREAD_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-thread-participant");
        }
    }
}
