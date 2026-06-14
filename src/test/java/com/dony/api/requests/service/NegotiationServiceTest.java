package com.dony.api.requests.service;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.StripeAccountStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.StorageService;
import com.dony.api.payments.cash.CommissionProperties;
import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.requests.CashGatePort;
import com.dony.api.requests.RequestsConfig;
import com.dony.api.requests.dto.NegotiationStartRequest;
import com.dony.api.requests.dto.NegotiationThreadResponse;
import com.dony.api.requests.entity.*;
import com.dony.api.requests.event.NegotiationStartedEvent;
import com.dony.api.requests.event.PackageRequestAcceptedEvent;
import com.dony.api.requests.event.NegotiationAwaitingTripEvent;
import com.dony.api.requests.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
    @Mock private com.dony.api.matching.AnnouncementRepository announcementRepo;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private RequestsConfig config;
    @Mock private CommissionProperties commissionProperties;
    @Mock private CashGatePort cashGatePort;
    @Mock private com.dony.api.requests.NegotiationEscrowPort escrowPort;
    @Mock private StorageService storageService;

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
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
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

        // Default commission rate used whenever toResponse() is called.
        // Lenient to avoid UnnecessaryStubbingException in error-path tests that never reach toResponse().
        lenient().when(commissionProperties.rate()).thenReturn(new BigDecimal("0.12"));
        // Pass-through for presigned avatar URLs
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
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
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findActiveByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
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
            when(threadRepo.findActiveByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
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
            when(threadRepo.findActiveByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
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
            when(threadRepo.findActiveByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
                .thenReturn(Optional.empty());
            when(threadRepo.countByTravelerIdAndStatus(eq(TRAVELER_ID), eq(NegotiationThreadStatus.OPEN)))
                .thenReturn(5L);

            assertThatThrownBy(() -> service.start(TRAVELER_ID, validStartReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("max-open-reached");
        }

        @Test
        @DisplayName("voyageur ne peut pas offrir le mode accepté par la demande → 422 payment-method/not-offerable")
        void start_rejectsWhenTravelerCannotOfferAnyAcceptedMethod() {
            // Request only accepts STRIPE
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.STRIPE));
            // Traveler is NOT onboarded on Stripe (default stripeAccountStatus = NOT_CREATED)
            traveler.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);

            when(config.maxOpenThreadsPerTraveler()).thenReturn(5);
            when(config.threadsPerMinuteRateLimit()).thenReturn(1);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findActiveByPackageRequestIdAndTravelerId(any(), any()))
                .thenReturn(Optional.empty());
            when(threadRepo.countByTravelerIdAndStatus(any(), any())).thenReturn(0L);
            when(threadRepo.countCreatedBy(any(), any())).thenReturn(0L);

            var req = new NegotiationStartRequest(REQUEST_ID, new BigDecimal("30"),
                LocalDate.now().plusDays(5), new BigDecimal("10"), null, "x");
            assertThatThrownBy(() -> service.start(TRAVELER_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("payment-method/not-offerable");
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
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));

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
    @DisplayName("accept() — atomic acceptance")
    class AcceptTests {
        private NegotiationThreadEntity thread;
        private final UUID THREAD_ID = UUID.randomUUID();

        @org.junit.jupiter.api.BeforeEach
        void setupThread() {
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new java.math.BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        @DisplayName("sender accept → thread AWAITING_TRIP, request still NEGOTIATING, competing threads untouched, event")
        void accept_byOwner_movesToAwaitingTrip() {
            UUID OTHER_THREAD_ID = UUID.randomUUID();
            var otherThread = new NegotiationThreadEntity();
            otherThread.setPackageRequestId(REQUEST_ID);
            otherThread.setTravelerId(UUID.randomUUID());
            otherThread.setStatus(NegotiationThreadStatus.OPEN);
            otherThread.setCurrentPriceEur(new java.math.BigDecimal("32"));
            otherThread.setRoundsCount((short) 1);
            otherThread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(otherThread, OTHER_THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }

            // Last message from the traveler — sender can now accept it (bilateral contract)
            var travelerProposal = NegotiationMessageEntity.create(
                THREAD_ID, TRAVELER_ID, NegotiationMessageKind.PROPOSAL,
                new java.math.BigDecimal("30"), null);

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(travelerProposal));
            when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(threadRepo.save(any())).thenReturn(thread);

            var req = new com.dony.api.requests.dto.NegotiationAcceptRequest("Deal!");
            var response = service.accept(SENDER_ID, THREAD_ID, req);

            assertThat(response.status()).isEqualTo(NegotiationThreadStatus.AWAITING_TRIP);
            assertThat(response.paymentIntentClientSecret()).isNull();
            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_TRIP);
            // Request unchanged at accept time — only finalizes to ACCEPTED after payment
            assertThat(request.getStatus()).isEqualTo(PackageRequestStatus.OPEN);
            // Competing threads untouched at accept time — auto-reject only at finalize
            assertThat(otherThread.getStatus()).isEqualTo(NegotiationThreadStatus.OPEN);
            verify(eventPublisher).publishEvent(any(com.dony.api.requests.event.NegotiationAwaitingTripEvent.class));
            verify(messageRepo, atLeastOnce()).save(argThat(m -> m.getKind() == NegotiationMessageKind.ACCEPT));
            // Mapper: thread sans bid matérialisé → DTO.materializedBidId == null
            assertThat(response.materializedBidId()).isNull();
        }

        @Test
        @DisplayName("toResponse propage materializedBidId du thread vers le DTO")
        void accept_mapsMaterializedBidId() {
            UUID materializedBidId = UUID.randomUUID();
            thread.setMaterializedBidId(materializedBidId);

            var travelerProposal = NegotiationMessageEntity.create(
                THREAD_ID, TRAVELER_ID, NegotiationMessageKind.PROPOSAL,
                new java.math.BigDecimal("30"), null);

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(travelerProposal));
            when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(threadRepo.save(any())).thenReturn(thread);

            var req = new com.dony.api.requests.dto.NegotiationAcceptRequest("Deal!");
            var response = service.accept(SENDER_ID, THREAD_ID, req);

            assertThat(response.materializedBidId()).isEqualTo(materializedBidId);
        }

        @Test
        @DisplayName("non-sender → 403")
        void accept_nonSender_throws403() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            UUID OUTSIDER = UUID.randomUUID();
            var req = new com.dony.api.requests.dto.NegotiationAcceptRequest(null);

            assertThatThrownBy(() -> service.accept(OUTSIDER, THREAD_ID, req))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("not-thread-participant");
        }

        @Test
        @DisplayName("voyageur accepte sans messages → 409 inconsistent-thread (contrat bilatéral)")
        void traveler_accepts_noMessages_throwsInconsistent() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of());

            var req = new com.dony.api.requests.dto.NegotiationAcceptRequest(null);

            assertThatThrownBy(() -> service.accept(TRAVELER_ID, THREAD_ID, req))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("inconsistent-thread");
        }

        @Test
        @DisplayName("thread déjà ACCEPTED → 409 already-finalized")
        void accept_alreadyAccepted_throws409() {
            thread.setStatus(NegotiationThreadStatus.ACCEPTED);
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            var req = new com.dony.api.requests.dto.NegotiationAcceptRequest(null);

            assertThatThrownBy(() -> service.accept(SENDER_ID, THREAD_ID, req))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("already-finalized");
        }

        @Test
        @DisplayName("expéditeur accepte le dernier message du voyageur → AWAITING_TRIP")
        void sender_accepts_travelerMessage_setsAwaitingTrip() {
            NegotiationMessageEntity lastMsg = NegotiationMessageEntity.create(
                THREAD_ID, TRAVELER_ID, NegotiationMessageKind.PROPOSAL,
                new java.math.BigDecimal("30"), null);

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(lastMsg));
            when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(threadRepo.save(any())).thenReturn(thread);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(config.maxNegotiationRounds()).thenReturn(5);

            var result = service.accept(SENDER_ID, THREAD_ID, null);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_TRIP);
            assertThat(result.status()).isEqualTo(NegotiationThreadStatus.AWAITING_TRIP);
        }

        @Test
        @DisplayName("voyageur accepte le dernier message du sender sans trajet lié → AWAITING_TRIP")
        void traveler_accepts_senderMessage_noTrip_setsAwaitingTrip() {
            thread.setTravelerAnnouncementId(null);
            NegotiationMessageEntity lastMsg = NegotiationMessageEntity.create(
                THREAD_ID, SENDER_ID, NegotiationMessageKind.COUNTER,
                new java.math.BigDecimal("28"), null);

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(lastMsg));
            when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(threadRepo.save(any())).thenReturn(thread);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(config.maxNegotiationRounds()).thenReturn(5);

            service.accept(TRAVELER_ID, THREAD_ID, null);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_TRIP);
        }

        @Test
        @DisplayName("voyageur accepte avec trajet déjà lié → AWAITING_PAYMENT direct")
        void traveler_accepts_withLinkedTrip_setsAwaitingPayment() {
            thread.setTravelerAnnouncementId(UUID.randomUUID());
            NegotiationMessageEntity lastMsg = NegotiationMessageEntity.create(
                THREAD_ID, SENDER_ID, NegotiationMessageKind.COUNTER,
                new java.math.BigDecimal("28"), null);

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(lastMsg));
            when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(threadRepo.save(any())).thenReturn(thread);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(config.maxNegotiationRounds()).thenReturn(5);

            service.accept(TRAVELER_ID, THREAD_ID, null);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
        }

        @Test
        @DisplayName("accepter son propre message → 409 not-your-turn")
        void accept_ownMessage_throwsNotYourTurn() {
            NegotiationMessageEntity lastMsg = NegotiationMessageEntity.create(
                THREAD_ID, SENDER_ID, NegotiationMessageKind.COUNTER,
                new java.math.BigDecimal("28"), null);

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(lastMsg));

            assertThatThrownBy(() -> service.accept(SENDER_ID, THREAD_ID, null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("not-your-turn");
        }

        @Test
        @DisplayName("tiers non participant → 403 not-thread-participant")
        void accept_thirdParty_throwsForbidden() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            UUID stranger = UUID.randomUUID();
            assertThatThrownBy(() -> service.accept(stranger, THREAD_ID, null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("not-thread-participant");
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
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));
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

    @Nested
    class RefuseTripTests {

        @Test
        void refuseTrip_asSender_movesToAwaitingTrip() {
            UUID threadId = UUID.randomUUID();
            UUID announcementId = UUID.randomUUID();

            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setTravelerAnnouncementId(announcementId);
            thread.setTravelerTravelDate(java.time.LocalDate.now());
            thread.setTravelerAvailableKg(new BigDecimal("5"));
            thread.setCurrentPriceEur(new BigDecimal("45"));
            thread.setRoundsCount((short) 2);
            thread.setLastActivityAt(java.time.LocalDateTime.now());

            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(config.maxNegotiationRounds()).thenReturn(5);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of());

            NegotiationThreadResponse resp = service.refuseTrip(SENDER_ID, threadId, "Trajet non adapté");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_TRIP);
            assertThat(thread.getTravelerAnnouncementId()).isNull();
            assertThat(resp.status()).isEqualTo(NegotiationThreadStatus.AWAITING_TRIP);
            assertThat(resp.linkedTrip()).isNull();
            verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(threadId), eq("TRIP_REFUSED"), eq(SENDER_ID), any());
            verify(eventPublisher).publishEvent(any(NegotiationAwaitingTripEvent.class));
            verify(threadRepo).save(thread);
        }

        @Test
        void refuseTrip_asTraveler_throws403() {
            UUID threadId = UUID.randomUUID();
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setTravelerAnnouncementId(UUID.randomUUID());
            thread.setTravelerTravelDate(java.time.LocalDate.now());
            thread.setTravelerAvailableKg(new BigDecimal("5"));
            thread.setCurrentPriceEur(new BigDecimal("45"));
            thread.setRoundsCount((short) 2);
            thread.setLastActivityAt(java.time.LocalDateTime.now());

            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.refuseTrip(TRAVELER_ID, threadId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        }

        @Test
        void refuseTrip_noTripLinked_throws409() {
            UUID threadId = UUID.randomUUID();
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setTravelerAnnouncementId(null);
            thread.setTravelerTravelDate(java.time.LocalDate.now());
            thread.setTravelerAvailableKg(new BigDecimal("5"));
            thread.setCurrentPriceEur(new BigDecimal("45"));
            thread.setRoundsCount((short) 2);
            thread.setLastActivityAt(java.time.LocalDateTime.now());

            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.refuseTrip(SENDER_ID, threadId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        }

        @Test
        void refuseTrip_wrongStatus_throws409() {
            UUID threadId = UUID.randomUUID();
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setTravelerAnnouncementId(UUID.randomUUID());
            thread.setTravelerTravelDate(java.time.LocalDate.now());
            thread.setTravelerAvailableKg(new BigDecimal("5"));
            thread.setCurrentPriceEur(new BigDecimal("45"));
            thread.setRoundsCount((short) 2);
            thread.setLastActivityAt(java.time.LocalDateTime.now());

            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.refuseTrip(SENDER_ID, threadId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        }
    }

    @Nested
    @DisplayName("createDedicatedTrip()")
    class CreateDedicatedTripTests {

        private final UUID THREAD_ID = UUID.randomUUID();
        private NegotiationThreadEntity thread;

        @BeforeEach
        void setupThread() {
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setDesiredDate(LocalDate.now().plusDays(10));
            request.setDateToleranceDays((short) 2);
            request.setWeightKg(new BigDecimal("5"));
            request.setTransportMode(com.dony.api.matching.TransportMode.PLANE);
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(com.dony.api.payments.cash.PaymentMethod.CASH));

            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_TRIP);
            thread.setCurrentPriceEur(new BigDecimal("80"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        private com.dony.api.requests.dto.NegotiationCreateDedicatedTripRequest buildRequest(LocalDate date) {
            return new com.dony.api.requests.dto.NegotiationCreateDedicatedTripRequest(
                date,
                java.time.LocalTime.of(8, 0),
                java.time.LocalTime.of(14, 30),
                new com.dony.api.matching.dto.AddressDto("CDG T2E", 49.0097, 2.5479),
                new com.dony.api.matching.dto.AddressDto("DSS Diass", 14.6708, -17.0734),
                "Bagage en soute",
                java.util.List.of("vetements", "documents"),
                java.util.List.of("liquides"),
                com.dony.api.payments.cash.PaymentMethod.CASH
            );
        }

        @Test
        @DisplayName("happy path — date dans la fenêtre → annonce dédiée créée + thread → AWAITING_PAYMENT + event")
        void createDedicatedTrip_valid_createsAnnouncementAndTransitionsThread() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(commissionProperties.rate()).thenReturn(new BigDecimal("0.12"));
            when(cashGatePort.hasSufficientFunds(eq(TRAVELER_ID), any())).thenReturn(true);
            UUID newAnnId = UUID.randomUUID();
            when(announcementRepo.save(any())).thenAnswer(inv -> {
                com.dony.api.matching.AnnouncementEntity a = inv.getArgument(0);
                try {
                    var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(a, newAnnId);
                } catch (Exception e) { throw new RuntimeException(e); }
                return a;
            });
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(java.util.List.of());

            var resp = service.createDedicatedTrip(TRAVELER_ID, THREAD_ID,
                buildRequest(request.getDesiredDate())); // exact desired date — within tolerance

            assertThat(resp.status()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
            assertThat(resp.travelerAnnouncementId()).isEqualTo(newAnnId);

            ArgumentCaptor<com.dony.api.matching.AnnouncementEntity> annCaptor =
                ArgumentCaptor.forClass(com.dony.api.matching.AnnouncementEntity.class);
            verify(announcementRepo).save(annCaptor.capture());
            com.dony.api.matching.AnnouncementEntity savedAnn = annCaptor.getValue();
            // Locked fields derived server-side
            assertThat(savedAnn.getDepartureCity()).isEqualTo("Paris");
            assertThat(savedAnn.getArrivalCity()).isEqualTo("Dakar");
            // Avant ouverture du surplus : aucune capacité dispo pour des tiers,
            // tout est réservé au sender → availableKg = 0 (carte « 5/5 réservés »).
            assertThat(savedAnn.getAvailableKg()).isEqualByComparingTo("0");
            assertThat(savedAnn.getTotalKg()).isEqualByComparingTo("5");
            assertThat(savedAnn.getTransportMode()).isEqualTo(com.dony.api.matching.TransportMode.PLANE);
            assertThat(savedAnn.getLinkedPackageRequestId()).isEqualTo(REQUEST_ID);
            // Surplus capacity: reservedKg = request weight, surplus locked at creation
            assertThat(savedAnn.getReservedKg()).isEqualByComparingTo("5");
            assertThat(savedAnn.isSurplusEligible()).isFalse();
            assertThat(savedAnn.isSurplusPublished()).isFalse();
            // Sender réservé mémorisé → il ne pourra pas re-bidder sur le surplus.
            assertThat(savedAnn.getReservedSenderId()).isEqualTo(SENDER_ID);
            // Price-per-kg derived from agreed total (80 / 5 = 16)
            assertThat(savedAnn.getPricePerKg()).isEqualByComparingTo("16.00");
            assertThat(savedAnn.getStatus()).isEqualTo(com.dony.api.matching.AnnouncementStatus.ACTIVE);

            verify(eventPublisher).publishEvent(any(com.dony.api.requests.event.NegotiationAwaitingPaymentEvent.class));
        }

        @Test
        @DisplayName("date avant la fenêtre de tolérance → 422 date-mismatch")
        void createDedicatedTrip_dateBeforeWindow_throws422() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            var req = buildRequest(request.getDesiredDate().minusDays(3));
            assertThatThrownBy(() -> service.createDedicatedTrip(TRAVELER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("date-mismatch");
            verifyNoInteractions(announcementRepo);
        }

        @Test
        @DisplayName("date après la fenêtre de tolérance → 422 date-mismatch")
        void createDedicatedTrip_dateAfterWindow_throws422() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            var req = buildRequest(request.getDesiredDate().plusDays(3));
            assertThatThrownBy(() -> service.createDedicatedTrip(TRAVELER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("date-mismatch");
            verifyNoInteractions(announcementRepo);
        }

        @Test
        @DisplayName("caller n'est pas le traveler → 403 not-traveler")
        void createDedicatedTrip_notTraveler_throws403() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));

            UUID OUTSIDER = UUID.randomUUID();
            assertThatThrownBy(() -> service.createDedicatedTrip(OUTSIDER, THREAD_ID,
                buildRequest(request.getDesiredDate())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-traveler");
            verifyNoInteractions(announcementRepo);
        }

        @Test
        @DisplayName("thread status != AWAITING_TRIP → 409 not-awaiting-trip")
        void createDedicatedTrip_wrongStatus_throws409() {
            thread.setStatus(NegotiationThreadStatus.OPEN);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));

            assertThatThrownBy(() -> service.createDedicatedTrip(TRAVELER_ID, THREAD_ID,
                buildRequest(request.getDesiredDate())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-awaiting-trip");
            verifyNoInteractions(announcementRepo);
        }

        @Test
        @DisplayName("thread introuvable → 404 thread/not-found")
        void createDedicatedTrip_threadMissing_throws404() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createDedicatedTrip(TRAVELER_ID, THREAD_ID,
                buildRequest(LocalDate.now().plusDays(10))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("thread/not-found");
        }

        @Test
        @DisplayName("voyageur choisit CASH avec fonds insuffisants → 422 traveler-insufficient-funds-cash")
        void createDedicatedTrip_rejectsCashWhenInsufficientFunds() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(commissionProperties.rate()).thenReturn(new BigDecimal("0.12"));
            when(cashGatePort.hasSufficientFunds(eq(TRAVELER_ID), any())).thenReturn(false);

            // buildRequest uses CASH as payment method
            assertThatThrownBy(() -> service.createDedicatedTrip(TRAVELER_ID, THREAD_ID,
                buildRequest(request.getDesiredDate())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("traveler-insufficient-funds-cash");

            verifyNoInteractions(announcementRepo);
        }
    }

    @Nested
    @DisplayName("submitTrip() — payment method validation")
    class SubmitTripPaymentMethodTests {

        private final UUID THREAD_ID = UUID.randomUUID();
        private NegotiationThreadEntity thread;

        @BeforeEach
        void setupThread() {
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setDesiredDate(LocalDate.now().plusDays(10));
            request.setDateToleranceDays((short) 2);
            request.setWeightKg(new BigDecimal("5"));
            // Request only accepts STRIPE
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.STRIPE));

            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_TRIP);
            thread.setCurrentPriceEur(new BigDecimal("80"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        @DisplayName("voyageur choisit CASH mais la demande n'accepte que STRIPE → 422 payment-method/not-accepted-by-request")
        void submitTrip_rejectsWhenPaymentMethodNotAcceptedByRequest() {
            UUID annId = UUID.randomUUID();
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            // Traveler tries CASH, but request only accepts STRIPE
            var req = new com.dony.api.requests.dto.NegotiationSubmitTripRequest(annId, PaymentMethod.CASH);

            assertThatThrownBy(() -> service.submitTrip(TRAVELER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("payment-method/not-accepted-by-request");
        }

        @Test
        @DisplayName("voyageur choisit CASH avec fonds insuffisants → 422 traveler-insufficient-funds-cash")
        void submitTrip_rejectsCashWhenInsufficientFunds() {
            // Request accepts CASH
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.CASH));
            UUID annId = UUID.randomUUID();

            com.dony.api.matching.AnnouncementEntity ann = new com.dony.api.matching.AnnouncementEntity();
            ann.setTravelerId(TRAVELER_ID);
            ann.setDepartureCity("Paris");
            ann.setArrivalCity("Dakar");
            ann.setDepartureDate(request.getDesiredDate());
            ann.setAvailableKg(new BigDecimal("5")); // request weight is 5 → linkable

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(announcementRepo.findById(annId)).thenReturn(Optional.of(ann));
            when(commissionProperties.rate()).thenReturn(new java.math.BigDecimal("0.12"));
            when(cashGatePort.hasSufficientFunds(eq(TRAVELER_ID), any())).thenReturn(false);

            var req = new com.dony.api.requests.dto.NegotiationSubmitTripRequest(annId, PaymentMethod.CASH);

            assertThatThrownBy(() -> service.submitTrip(TRAVELER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("traveler-insufficient-funds-cash");
        }

        @Test
        @DisplayName("CASH + consentement carte + carte enregistrée → liaison OK même wallet vide (wallet non consulté)")
        void submitTrip_cashWithCardConsent_linksEvenIfWalletShort() {
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.CASH));
            UUID annId = UUID.randomUUID();

            com.dony.api.matching.AnnouncementEntity ann = new com.dony.api.matching.AnnouncementEntity();
            ann.setTravelerId(TRAVELER_ID);
            ann.setDepartureCity("Paris");
            ann.setArrivalCity("Dakar");
            ann.setDepartureDate(request.getDesiredDate());
            ann.setAvailableKg(new BigDecimal("5")); // request weight is 5 → linkable

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(announcementRepo.findById(annId)).thenReturn(Optional.of(ann));
            when(cashGatePort.hasCommissionCard(eq(TRAVELER_ID))).thenReturn(true);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());

            var req = new com.dony.api.requests.dto.NegotiationSubmitTripRequest(
                annId, PaymentMethod.CASH, true);
            var resp = service.submitTrip(TRAVELER_ID, THREAD_ID, req);

            assertThat(resp.status()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
            assertThat(thread.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
            // Chemin carte : le solde wallet n'est pas consulté.
            verify(cashGatePort, org.mockito.Mockito.never()).hasSufficientFunds(any(), any());
        }

        @Test
        @DisplayName("CASH + consentement carte mais aucune carte enregistrée → 422 no-commission-card")
        void submitTrip_cashWithCardConsentButNoCard_throws422() {
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.CASH));
            UUID annId = UUID.randomUUID();

            com.dony.api.matching.AnnouncementEntity ann = new com.dony.api.matching.AnnouncementEntity();
            ann.setTravelerId(TRAVELER_ID);
            ann.setDepartureCity("Paris");
            ann.setArrivalCity("Dakar");
            ann.setDepartureDate(request.getDesiredDate());
            ann.setAvailableKg(new BigDecimal("5")); // request weight is 5 → linkable

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(announcementRepo.findById(annId)).thenReturn(Optional.of(ann));
            when(cashGatePort.hasCommissionCard(eq(TRAVELER_ID))).thenReturn(false);

            var req = new com.dony.api.requests.dto.NegotiationSubmitTripRequest(
                annId, PaymentMethod.CASH, true);

            assertThatThrownBy(() -> service.submitTrip(TRAVELER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no-commission-card");
        }

        @Test
        @DisplayName("annonce non ACTIVE (ex. COMPLETED) → 422 announcement/not-active")
        void submitTrip_rejectsWhenAnnouncementNotActive() {
            UUID annId = UUID.randomUUID();
            com.dony.api.matching.AnnouncementEntity ann = new com.dony.api.matching.AnnouncementEntity();
            ann.setTravelerId(TRAVELER_ID);
            ann.setDepartureCity("Paris");
            ann.setArrivalCity("Dakar");
            ann.setDepartureDate(request.getDesiredDate());
            ann.setAvailableKg(new BigDecimal("5"));
            // Trip already finished: linking it would leave it stuck out of "À venir".
            ann.setStatus(com.dony.api.matching.AnnouncementStatus.COMPLETED);

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(announcementRepo.findById(annId)).thenReturn(Optional.of(ann));

            var req = new com.dony.api.requests.dto.NegotiationSubmitTripRequest(annId, PaymentMethod.STRIPE);

            assertThatThrownBy(() -> service.submitTrip(TRAVELER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("announcement/not-active");
        }

        @Test
        @DisplayName("annonce ACTIVE mais capacité insuffisante → 422 announcement/insufficient-capacity")
        void submitTrip_rejectsWhenInsufficientCapacity() {
            UUID annId = UUID.randomUUID();
            com.dony.api.matching.AnnouncementEntity ann = new com.dony.api.matching.AnnouncementEntity();
            ann.setTravelerId(TRAVELER_ID);
            ann.setDepartureCity("Paris");
            ann.setArrivalCity("Dakar");
            ann.setDepartureDate(request.getDesiredDate());
            ann.setStatus(com.dony.api.matching.AnnouncementStatus.ACTIVE);
            ann.setAvailableKg(new BigDecimal("2")); // request weight is 5 → too small

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(announcementRepo.findById(annId)).thenReturn(Optional.of(ann));

            var req = new com.dony.api.requests.dto.NegotiationSubmitTripRequest(annId, PaymentMethod.STRIPE);

            assertThatThrownBy(() -> service.submitTrip(TRAVELER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("announcement/insufficient-capacity");
        }
    }

    @Nested
    @DisplayName("Firm-price (negotiable=false)")
    class FirmPriceTests {

        @Test
        @DisplayName("start() avec prix ferme et prix proposé ≠ targetPrice → 422 firm-price")
        void start_firmRequest_priceMustEqualTarget() {
            request.setNegotiable(false);
            request.setTargetPriceEur(new BigDecimal("35"));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            var bad = new NegotiationStartRequest(REQUEST_ID, new BigDecimal("30"),
                LocalDate.now().plusDays(5), new BigDecimal("10"), null, "x");
            assertThatThrownBy(() -> service.start(TRAVELER_ID, bad))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("firm-price");
        }

        @Test
        @DisplayName("counter() sur request avec prix ferme → 409 counter-not-allowed-firm-price")
        void counter_firmRequest_forbidden() {
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setRoundsCount((short) 1);
            request.setNegotiable(false);
            when(threadRepo.findById(any())).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.counter(SENDER_ID, UUID.randomUUID(),
                    new com.dony.api.requests.dto.NegotiationCounterRequest(new BigDecimal("33"), "non")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("counter-not-allowed-firm-price");
        }
    }

    @Nested
    @DisplayName("finalizeAfterPayment() — details completeness check")
    class FinalizeAfterPaymentTests {

        private final UUID THREAD_ID = UUID.randomUUID();
        private NegotiationThreadEntity thread;

        @BeforeEach
        void setupThread() {
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setCurrentPriceEur(new BigDecimal("35"));
            thread.setRoundsCount((short) 2);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        @DisplayName("détails incomplets (recipientName null) → 422 details-incomplete")
        void finalize_requiresCompleteDetails() {
            request.setRecipientName(null); // détails incomplets intentionnellement
            request.setRecipientPhone("+221771234567");
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("details-incomplete");
        }

        @Test
        @DisplayName("détails incomplets (recipientPhone null) → 422 details-incomplete")
        void finalize_requiresRecipientPhone() {
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone(null);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("details-incomplete");
        }

        @Test
        @DisplayName("name + phone seuls (sans adresse/declaredValue) → finalize OK")
        void finalize_onlyNameAndPhone_succeeds() {
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
            // address, declaredValue, disclaimer left null on purpose
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_real_789");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            assertThat(request.getStatus()).isEqualTo(PackageRequestStatus.ACCEPTED);
        }

        @Test
        @DisplayName("thread CASH — commission prélevée (port=true) → finalize OK + ACCEPTED")
        void finalize_cashThread_commissionCharged_succeeds() {
            thread.setPaymentMethod(com.dony.api.payments.cash.PaymentMethod.CASH);
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(cashGatePort.chargeNegotiationCashCommission(eq(TRAVELER_ID), eq(SENDER_ID), eq(THREAD_ID), any()))
                .thenReturn(true);
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_real_cash");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            assertThat(request.getStatus()).isEqualTo(PackageRequestStatus.ACCEPTED);
            verify(cashGatePort).chargeNegotiationCashCommission(eq(TRAVELER_ID), eq(SENDER_ID), eq(THREAD_ID),
                eq(thread.getCurrentPriceEur()));
            org.mockito.ArgumentCaptor<PackageRequestAcceptedEvent> captor =
                org.mockito.ArgumentCaptor.forClass(PackageRequestAcceptedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().paymentMethod())
                .isEqualTo(com.dony.api.payments.cash.PaymentMethod.CASH);
        }

        @Test
        @DisplayName("thread CASH — commission échoue (port=false) → 422 et thread reste AWAITING_PAYMENT")
        void finalize_cashThread_commissionFails_throws422AndNotFinalized() {
            thread.setPaymentMethod(com.dony.api.payments.cash.PaymentMethod.CASH);
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(cashGatePort.chargeNegotiationCashCommission(eq(TRAVELER_ID), eq(SENDER_ID), eq(THREAD_ID), any()))
                .thenReturn(false);

            assertThatThrownBy(() -> service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("negotiation/commission-charge-failed");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
            verify(eventPublisher, never()).publishEvent(any(PackageRequestAcceptedEvent.class));
        }

        @Test
        @DisplayName("idempotent : thread déjà ACCEPTED avec le même PaymentIntent → succès sans 409 ni event ré-publié")
        void finalize_alreadyAcceptedSamePaymentIntent_isIdempotent() {
            // Race du double finalize (/checkout synchrone + webhook Stripe) : le premier a
            // déjà accepté ce thread avec CE PaymentIntent. Le second ne doit PAS lever 409 ni
            // re-publier l'event (sinon bid/QR/tracking en double) → retour idempotent ACCEPTED.
            thread.setStatus(NegotiationThreadStatus.ACCEPTED);
            thread.setPaymentIntentId("pi_already");
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));

            var resp = service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_already");

            assertThat(resp.status()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            verify(eventPublisher, never()).publishEvent(any(PackageRequestAcceptedEvent.class));
            verify(threadRepo, never()).save(any()); // pas de re-finalize
        }

        @Test
        @DisplayName("vrai conflit : thread REJECTED → 409 not-awaiting-payment (pas idempotent)")
        void finalize_rejectedThread_throws409() {
            thread.setStatus(NegotiationThreadStatus.REJECTED);
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-awaiting-payment");
        }

        @Test
        @DisplayName("trajet dédié finalisé → l'annonce liée devient surplusEligible")
        void finalize_dedicatedTrip_marksAnnouncementSurplusEligible() {
            UUID annId = UUID.randomUUID();
            thread.setTravelerAnnouncementId(annId);
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            com.dony.api.matching.AnnouncementEntity dedicatedAnn = new com.dony.api.matching.AnnouncementEntity();
            dedicatedAnn.setLinkedPackageRequestId(REQUEST_ID); // dédié
            dedicatedAnn.setReservedKg(new BigDecimal("5"));

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(announcementRepo.findById(annId)).thenReturn(Optional.of(dedicatedAnn));

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_dedicated");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            assertThat(dedicatedAnn.isSurplusEligible()).isTrue();
            verify(announcementRepo).save(dedicatedAnn);
        }

        @Test
        @DisplayName("trajet existant (non dédié) finalisé → l'annonce n'est PAS marquée éligible")
        void finalize_nonDedicatedTrip_doesNotMarkSurplusEligible() {
            UUID annId = UUID.randomUUID();
            thread.setTravelerAnnouncementId(annId);
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            com.dony.api.matching.AnnouncementEntity publicAnn = new com.dony.api.matching.AnnouncementEntity();
            publicAnn.setLinkedPackageRequestId(null); // trajet public/existant

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(announcementRepo.findById(annId)).thenReturn(Optional.of(publicAnn));

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_public");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            assertThat(publicAnn.isSurplusEligible()).isFalse();
            // not a dedicated trip → no surplus-eligibility save on the announcement
            verify(announcementRepo, never()).save(publicAnn);
        }
    }

    @Nested
    @DisplayName("checkout() — idempotence vs finalize webhook concurrent")
    class CheckoutIdempotencyTests {

        private final UUID THREAD_ID = UUID.randomUUID();
        private NegotiationThreadEntity thread;

        @BeforeEach
        void setupThread() {
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setCurrentPriceEur(new BigDecimal("35"));
            thread.setRoundsCount((short) 2);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
        }

        @Test
        @DisplayName("webhook gagne la course du @Version (optimistic lock) → succès ACCEPTED, pas de 409")
        void checkout_optimisticLockLoser_returnsAcceptedSuccess() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(escrowPort.verifyNegotiationEscrow(eq(THREAD_ID), any())).thenReturn(true);
            // Le webhook concurrent a déjà commité → notre save perd la course du @Version.
            when(threadRepo.save(any())).thenThrow(
                new org.springframework.orm.ObjectOptimisticLockingFailureException(
                    NegotiationThreadEntity.class, THREAD_ID));
            // Relecture (getById) : finalizeInternal a déjà passé le thread à ACCEPTED en
            // mémoire avant le save qui échoue — comme le webhook gagnant l'a fait en base.
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));

            var resp = service.checkout(SENDER_ID, THREAD_ID, "pi_real", null);

            assertThat(resp.status()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
        }

        @Test
        @DisplayName("thread déjà ACCEPTED (webhook a finalisé avant la lecture) → succès idempotent")
        void checkout_threadAlreadyAccepted_returnsSuccess() {
            thread.setStatus(NegotiationThreadStatus.ACCEPTED);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));

            var resp = service.checkout(SENDER_ID, THREAD_ID, "pi_real", null);

            assertThat(resp.status()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            // pas de re-finalize → aucun event ré-publié (pas de bid/QR/tracking en double)
            verify(eventPublisher, never()).publishEvent(any(PackageRequestAcceptedEvent.class));
        }

        @Test
        @DisplayName("vrai conflit (thread REJECTED, pas une course) → 409 propagé au caller")
        void checkout_genuineConflict_rethrows() {
            thread.setStatus(NegotiationThreadStatus.REJECTED);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> service.checkout(SENDER_ID, THREAD_ID, "pi_real", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-awaiting-payment");
        }
    }

    @Nested
    @DisplayName("openSurplus()")
    class OpenSurplusTests {

        private final UUID ANN_ID = UUID.randomUUID();
        private com.dony.api.matching.AnnouncementEntity ann;
        private NegotiationThreadEntity thread;

        @BeforeEach
        void setupAnnAndThread() {
            ann = new com.dony.api.matching.AnnouncementEntity();
            ann.setTravelerId(TRAVELER_ID);
            ann.setLinkedPackageRequestId(REQUEST_ID); // dédié
            ann.setReservedKg(new BigDecimal("5"));
            ann.setAvailableKg(new BigDecimal("5"));
            ann.setTotalKg(new BigDecimal("5"));
            ann.setPricePerKg(new BigDecimal("16.00"));
            ann.setSurplusEligible(true);
            ann.setSurplusPublished(false);

            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.ACCEPTED);
            thread.setCurrentPriceEur(new BigDecimal("80"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
        }

        @Test
        @DisplayName("succès → availableKg=surplus, totalKg=reserved+surplus, pricePerKg=surplusPrice, surplusPublished")
        void openSurplus_success() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            when(threadRepo.findByTravelerAnnouncementId(ANN_ID)).thenReturn(Optional.of(thread));
            when(announcementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.openSurplus(TRAVELER_ID, ANN_ID, new BigDecimal("8"), new BigDecimal("7"));

            assertThat(ann.getAvailableKg()).isEqualByComparingTo("8");
            assertThat(ann.getTotalKg()).isEqualByComparingTo("13"); // reserved 5 + surplus 8
            assertThat(ann.getPricePerKg()).isEqualByComparingTo("7");
            assertThat(ann.isSurplusPublished()).isTrue();
            verify(announcementRepo).save(ann);
            verify(auditService).log(eq("ANNOUNCEMENT"), eq(ANN_ID), eq("SURPLUS_OPENED"), eq(TRAVELER_ID), anyMap());
        }

        @Test
        @DisplayName("annonce introuvable → 404")
        void openSurplus_notFound_throws404() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("8"), new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("announcement/not-found");
        }

        @Test
        @DisplayName("caller ≠ voyageur → 403 negotiation/not-traveler")
        void openSurplus_notTraveler_throws403() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            assertThatThrownBy(() -> service.openSurplus(UUID.randomUUID(), ANN_ID,
                new BigDecimal("8"), new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("negotiation/not-traveler");
            verify(announcementRepo, never()).save(any());
        }

        @Test
        @DisplayName("trajet non dédié (linkedPackageRequestId null) → 422 surplus/not-dedicated")
        void openSurplus_notDedicated_throws422() {
            ann.setLinkedPackageRequestId(null);
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("8"), new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/not-dedicated");
        }

        @Test
        @DisplayName("déjà publié → 409 surplus/already-open")
        void openSurplus_alreadyOpen_throws409() {
            ann.setSurplusPublished(true);
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("8"), new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/already-open");
        }

        @Test
        @DisplayName("surplusKg < 1 → 422 surplus/invalid-kg")
        void openSurplus_invalidKg_throws422() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("0.5"), new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/invalid-kg");
        }

        @Test
        @DisplayName("surplusKg null → 422 surplus/invalid-kg")
        void openSurplus_nullKg_throws422() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                null, new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/invalid-kg");
        }

        @Test
        @DisplayName("pricePerKg <= 0 → 422 surplus/invalid-price")
        void openSurplus_invalidPrice_throws422() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("8"), BigDecimal.ZERO))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/invalid-price");
        }

        @Test
        @DisplayName("pricePerKg null → 422 surplus/invalid-price")
        void openSurplus_nullPrice_throws422() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("8"), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/invalid-price");
        }

        @Test
        @DisplayName("aucun thread pour le trajet → 409 surplus/negotiation-not-accepted")
        void openSurplus_noThread_throws409() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            when(threadRepo.findByTravelerAnnouncementId(ANN_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("8"), new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/negotiation-not-accepted");
            verify(announcementRepo, never()).save(any());
        }

        @Test
        @DisplayName("thread non ACCEPTED → 409 surplus/negotiation-not-accepted")
        void openSurplus_threadNotAccepted_throws409() {
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            when(threadRepo.findByTravelerAnnouncementId(ANN_ID)).thenReturn(Optional.of(thread));
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("8"), new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/negotiation-not-accepted");
            verify(announcementRepo, never()).save(any());
        }
    }

    @Nested
    @DisplayName("toResponse() — Modèle B champs calculés")
    class ToResponseModelBTests {

        @Test
        @DisplayName("grossPriceEur exposé = net * (1 + rate) — modèle B")
        void toResponse_exposesGrossPrice_modelB() {
            // Préparer un thread OPEN avec currentPriceEur (net) = 35
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("35"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, UUID.randomUUID());
            } catch (Exception e) { throw new RuntimeException(e); }

            // commission rate = 0.12 (already stubbed in @BeforeEach)

            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            // Appeler toResponse directement
            NegotiationThreadResponse response = service.toResponse(
                thread, List.of(), null, traveler, request, TRAVELER_ID, "Expéditeur", null);

            // Asserter: grossPriceEur ≈ 39.20 (35 * 1.12)
            assertThat(response.grossPriceEur())
                .isNotNull()
                .isEqualByComparingTo("39.20");

            // paymentMethod is null (not set on entity)
            assertThat(response.paymentMethod()).isNull();
        }

        @Test
        @DisplayName("currentPriceEur null → grossPriceEur null (pas de NPE)")
        void toResponse_nullCurrentPrice_returnsNullGross() {
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(null); // prix non encore fixé
            thread.setRoundsCount((short) 0);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, UUID.randomUUID());
            } catch (Exception e) { throw new RuntimeException(e); }

            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            // Ne doit pas lancer de NullPointerException
            NegotiationThreadResponse response = service.toResponse(
                thread, List.of(), null, traveler, request, TRAVELER_ID, "Expéditeur", null);

            assertThat(response.grossPriceEur()).isNull();
            assertThat(response.currentPriceEur()).isNull();
        }

        @Test
        @DisplayName("paymentMethod exposé depuis l'entité thread")
        void toResponse_exposesPaymentMethod() {
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("40"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            thread.setPaymentMethod(com.dony.api.payments.cash.PaymentMethod.WAVE);
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, UUID.randomUUID());
            } catch (Exception e) { throw new RuntimeException(e); }

            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            NegotiationThreadResponse response = service.toResponse(
                thread, List.of(), null, traveler, request, TRAVELER_ID, "Expéditeur", null);

            assertThat(response.paymentMethod()).isEqualTo(com.dony.api.payments.cash.PaymentMethod.WAVE);
        }

        @Test
        @DisplayName("linkedAnn KG_FREE → capacityUnit exposé dans linkedTrip ET travelerCapacityUnit")
        void toResponse_kgFree_exposesCapacityUnit() {
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, UUID.randomUUID());
            } catch (Exception e) { throw new RuntimeException(e); }

            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            com.dony.api.matching.AnnouncementEntity linkedAnn =
                new com.dony.api.matching.AnnouncementEntity();
            linkedAnn.setDepartureCity("Paris");
            linkedAnn.setArrivalCity("Dakar");
            linkedAnn.setAvailableKg(new BigDecimal("1"));
            linkedAnn.setCapacityUnit(com.dony.api.matching.CapacityUnit.KG_FREE);
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(linkedAnn, UUID.randomUUID());
            } catch (Exception e) { throw new RuntimeException(e); }

            NegotiationThreadResponse response = service.toResponse(
                thread, List.of(), null, traveler, request, TRAVELER_ID, "Expéditeur", linkedAnn);

            assertThat(response.linkedTrip()).isNotNull();
            assertThat(response.linkedTrip().capacityUnit()).isEqualTo("KG_FREE");
            assertThat(response.travelerCapacityUnit()).isEqualTo("KG_FREE");
        }

        @Test
        @DisplayName("linkedAnn null → travelerCapacityUnit null (fallback kg côté front)")
        void toResponse_nullLinkedAnn_nullCapacityUnit() {
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, UUID.randomUUID());
            } catch (Exception e) { throw new RuntimeException(e); }

            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            NegotiationThreadResponse response = service.toResponse(
                thread, List.of(), null, traveler, request, TRAVELER_ID, "Expéditeur", null);

            assertThat(response.linkedTrip()).isNull();
            assertThat(response.travelerCapacityUnit()).isNull();
        }
    }

    // ─── Task 13 — tests supplémentaires pour couvrir listMine, listForRequest,
    //              finalizeAfterPayment happy-path, et NegotiationPaymentAuthorizedEvent ─────────

    @Nested
    @DisplayName("listMine() — threads du participant")
    class ListMineTests {

        @Test
        @DisplayName("listMine() retourne les threads de l'utilisateur avec toResponse mappé")
        void listMine_returnsThreadsForUser() {
            UUID threadId = UUID.randomUUID();
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }

            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            when(threadRepo.findByParticipant(TRAVELER_ID)).thenReturn(List.of(thread));
            when(announcementRepo.findAllById(any())).thenReturn(List.of());
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler)); // reuse as sender

            List<com.dony.api.requests.dto.NegotiationThreadResponse> result = service.listMine(TRAVELER_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).currentPriceEur()).isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("listMine() ignore les threads avec request ou traveler soft-deleted (retourne vide)")
        void listMine_skipsOrphanedThreads() {
            UUID threadId = UUID.randomUUID();
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("25"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }

            when(threadRepo.findByParticipant(TRAVELER_ID)).thenReturn(List.of(thread));
            when(announcementRepo.findAllById(any())).thenReturn(List.of());
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.empty()); // orphaned

            List<com.dony.api.requests.dto.NegotiationThreadResponse> result = service.listMine(TRAVELER_ID);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("listForRequest() — threads d'une demande")
    class ListForRequestTests {

        @Test
        @DisplayName("listForRequest() retourne les threads quand le caller est le sender")
        void listForRequest_returnThreadsForSender() {
            UUID threadId = UUID.randomUUID();
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("28"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }

            request.setDepartureCity("Lyon");
            request.setArrivalCity("Abidjan");
            request.setWeightKg(new BigDecimal("3"));

            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of(thread));
            when(announcementRepo.findAllById(any())).thenReturn(List.of());
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));

            List<com.dony.api.requests.dto.NegotiationThreadResponse> result =
                service.listForRequest(SENDER_ID, REQUEST_ID);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("listForRequest() throws 403 si le caller n'est pas le sender")
        void listForRequest_throwsForbiddenForNonSender() {
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.listForRequest(TRAVELER_ID, REQUEST_ID))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("forbidden");
        }

        @Test
        @DisplayName("listForRequest() throws 404 si la request n'existe pas")
        void listForRequest_throwsNotFoundForMissingRequest() {
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.listForRequest(SENDER_ID, REQUEST_ID))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("not-found");
        }
    }

    @Nested
    @DisplayName("finalizeAfterPayment() — chemin nominal")
    class FinalizeAfterPaymentHappyPathTests {

        private final UUID THREAD_ID = UUID.randomUUID();
        private NegotiationThreadEntity thread;

        @BeforeEach
        void setupThread() {
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setCurrentPriceEur(new BigDecimal("35"));
            thread.setRoundsCount((short) 2);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }

            // Complet request details
            request.setRecipientName("Mamadou Diallo");
            request.setRecipientPhone("+221771234567");
            request.setPickupAddressLabel("10 rue de la Paix, Paris");
            request.setDeliveryAddressLabel("Plateau, Dakar");
            request.setDeclaredValueEur(new BigDecimal("150"));
            request.setDisclaimerSignedAt(java.time.LocalDateTime.now());
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));
        }

        @Test
        @DisplayName("finalize avec détails complets → thread ACCEPTED, request ACCEPTED, event publié")
        void finalize_completeDetails_acceptsThread() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of()); // no competing
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            // thread.getTravelerAnnouncementId() is null → announcementRepo not called

            com.dony.api.requests.dto.NegotiationThreadResponse result =
                service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_real_123");

            assertThat(result).isNotNull();
            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            assertThat(request.getStatus()).isEqualTo(PackageRequestStatus.ACCEPTED);

            org.mockito.ArgumentCaptor<PackageRequestAcceptedEvent> captor =
                org.mockito.ArgumentCaptor.forClass(PackageRequestAcceptedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            PackageRequestAcceptedEvent published = captor.getValue();
            assertThat(published.recipientName()).isEqualTo("Mamadou Diallo");
            assertThat(published.recipientPhone()).isEqualTo("+221771234567");
            assertThat(published.declaredValueEur()).isEqualByComparingTo(new BigDecimal("150"));
            assertThat(published.disclaimerSignedAt()).isEqualTo(request.getDisclaimerSignedAt());
        }

        @Test
        @DisplayName("finalize auto-rejette les threads concurrents OPEN/AWAITING_TRIP/AWAITING_PAYMENT")
        void finalize_autoRejectsCompetingThreads() {
            NegotiationThreadEntity competing = new NegotiationThreadEntity();
            competing.setPackageRequestId(REQUEST_ID);
            competing.setTravelerId(UUID.randomUUID());
            competing.setStatus(NegotiationThreadStatus.OPEN);
            competing.setCurrentPriceEur(new BigDecimal("40"));
            competing.setRoundsCount((short) 1);
            competing.setLastActivityAt(java.time.LocalDateTime.now());
            UUID competingId = UUID.randomUUID();
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(competing, competingId);
            } catch (Exception e) { throw new RuntimeException(e); }

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of(thread, competing));
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            // thread.getTravelerAnnouncementId() is null → announcementRepo not called

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_real_456");

            assertThat(competing.getStatus()).isEqualTo(NegotiationThreadStatus.AUTO_REJECTED);
        }

        @Test
        @DisplayName("finalize throws 409 si le thread n'est pas en AWAITING_PAYMENT")
        void finalize_wrongStatus_throws409() {
            thread.setStatus(NegotiationThreadStatus.OPEN);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_x"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("not-awaiting-payment");
        }

        @Test
        @DisplayName("finalize throws 403 si le caller n'est pas le sender")
        void finalize_nonSender_throws403() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.finalizeAfterPayment(TRAVELER_ID, THREAD_ID, "pi_x"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("not-thread-participant");
        }

        @Test
        @DisplayName("finalize avec un mode de paiement non autorisé par la demande → 422")
        void finalize_chosenMethodNotAccepted_throws422() {
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.STRIPE));
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() ->
                    service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_x", PaymentMethod.CASH))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("payment-method/not-accepted");
        }

        @Test
        @DisplayName("finalize applique le mode choisi par l'expéditeur (override du thread, bascule cash→stripe)")
        void finalize_chosenMethodOverridesThreadMethod() {
            request.setAcceptedPaymentMethods(
                java.util.EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
            thread.setPaymentMethod(PaymentMethod.CASH);

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            // Bascule de méthode → on libère d'abord tout escrow en vol (ici aucun).
            when(escrowPort.releaseEscrowForMethodSwitch(THREAD_ID)).thenReturn(true);
            // Override vers STRIPE → /checkout vérifie l'escrow online.
            when(escrowPort.verifyNegotiationEscrow(THREAD_ID, "pi_real_override")).thenReturn(true);

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_real_override", PaymentMethod.STRIPE);

            // Le mode du thread est remplacé par celui choisi par l'expéditeur ;
            // la branche cash (commission) n'est donc PAS déclenchée.
            assertThat(thread.getPaymentMethod()).isEqualTo(PaymentMethod.STRIPE);
            org.mockito.Mockito.verifyNoInteractions(cashGatePort);
        }
    }

    @Nested
    @DisplayName("checkout() — vérification de l'escrow online (anti-bypass paiement)")
    class CheckoutEscrowVerificationTests {

        private final UUID THREAD_ID = UUID.randomUUID();
        private NegotiationThreadEntity thread;

        private void setId(Object entity, UUID id) {
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(entity, id);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @BeforeEach
        void setupAwaitingPaymentThread() {
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setCurrentPriceEur(new BigDecimal("35"));
            thread.setRoundsCount((short) 2);
            thread.setPaymentMethod(PaymentMethod.STRIPE);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            setId(thread, THREAD_ID);

            request.setAcceptedPaymentMethods(
                java.util.EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
            request.setRecipientName("Mamadou Diallo");
            request.setRecipientPhone("+221771234567");
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));
        }

        @Test
        @DisplayName("PaymentIntent non vérifié par Stripe → 422, thread reste AWAITING_PAYMENT, aucun event")
        void checkout_stripeUnverifiedEscrow_throws422_doesNotFinalize() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(escrowPort.verifyNegotiationEscrow(THREAD_ID, "x")).thenReturn(false);

            assertThatThrownBy(() ->
                    service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "x", PaymentMethod.STRIPE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("escrow-not-verified");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
            assertThat(request.getStatus()).isNotEqualTo(PackageRequestStatus.ACCEPTED);
            verify(eventPublisher, never()).publishEvent(any(PackageRequestAcceptedEvent.class));
        }

        @Test
        @DisplayName("PaymentIntent vérifié (requires_capture) → thread ACCEPTED + event")
        void checkout_stripeVerifiedEscrow_finalizes() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(escrowPort.verifyNegotiationEscrow(THREAD_ID, "pi_real_ok")).thenReturn(true);

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_real_ok", PaymentMethod.STRIPE);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            verify(eventPublisher).publishEvent(any(PackageRequestAcceptedEvent.class));
        }

        @Test
        @DisplayName("switch vers CASH → libère l'escrow Stripe puis finalise en CASH")
        void checkout_switchToCash_releasesEscrowThenFinalizes() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(escrowPort.releaseEscrowForMethodSwitch(THREAD_ID)).thenReturn(true);
            when(cashGatePort.chargeNegotiationCashCommission(
                    eq(TRAVELER_ID), eq(SENDER_ID), eq(THREAD_ID), any())).thenReturn(true);

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "CASH", PaymentMethod.CASH);

            verify(escrowPort).releaseEscrowForMethodSwitch(THREAD_ID);
            assertThat(thread.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            verify(cashGatePort).chargeNegotiationCashCommission(
                eq(TRAVELER_ID), eq(SENDER_ID), eq(THREAD_ID), any());
        }

        @Test
        @DisplayName("switch vers CASH mais escrow Stripe impossible à libérer → 409, pas de bascule ni de finalize")
        void checkout_switchToCash_releaseFails_throws409() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(escrowPort.releaseEscrowForMethodSwitch(THREAD_ID)).thenReturn(false);

            assertThatThrownBy(() ->
                    service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "CASH", PaymentMethod.CASH))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("escrow-release-failed");

            assertThat(thread.getPaymentMethod()).isEqualTo(PaymentMethod.STRIPE); // pas de bascule
            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
            verifyNoInteractions(cashGatePort);
            verify(eventPublisher, never()).publishEvent(any(PackageRequestAcceptedEvent.class));
        }

        @Test
        @DisplayName("webhook (3-arg) finalise un thread STRIPE sans appeler escrowPort (déjà vérifié par Stripe)")
        void webhookFinalize_stripe_doesNotCallEscrowPort() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_webhook");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            verifyNoInteractions(escrowPort);
        }
    }

    // ========== AvatarUrl in NegotiationThreadResponse ==========

    @Nested
    @DisplayName("toResponse() — photo URLs")
    class PhotoUrlTests {

        private final UUID THREAD_ID = UUID.randomUUID();

        private NegotiationThreadEntity buildThread() {
            NegotiationThreadEntity t = new NegotiationThreadEntity();
            t.setPackageRequestId(REQUEST_ID);
            t.setTravelerId(TRAVELER_ID);
            t.setStatus(NegotiationThreadStatus.OPEN);
            t.setCurrentPriceEur(new BigDecimal("30"));
            t.setRoundsCount((short) 1);
            t.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(t, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
            return t;
        }

        @Test
        @DisplayName("travelerPhotoUrl mappé depuis UserEntity du voyageur")
        void toResponse_travelerPhotoUrl_isMapped() {
            traveler.setAvatarUrl("https://cdn.example.com/traveler.jpg");
            request.setNegotiable(true);
            NegotiationThreadEntity thread = buildThread();

            when(config.maxNegotiationRounds()).thenReturn(5);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler)); // sender returns same user

            var response = service.getById(SENDER_ID, THREAD_ID);

            assertThat(response.travelerPhotoUrl()).isEqualTo("https://cdn.example.com/traveler.jpg");
        }

        @Test
        @DisplayName("senderPhotoUrl mappé depuis UserEntity de l'expéditeur")
        void toResponse_senderPhotoUrl_isMapped() {
            UserEntity sender = new UserEntity();
            sender.setAvatarUrl("https://cdn.example.com/sender.jpg");
            request.setNegotiable(true);
            NegotiationThreadEntity thread = buildThread();

            when(config.maxNegotiationRounds()).thenReturn(5);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            var response = service.getById(SENDER_ID, THREAD_ID);

            assertThat(response.senderPhotoUrl()).isEqualTo("https://cdn.example.com/sender.jpg");
        }

        @Test
        @DisplayName("sender introuvable → senderPhotoUrl null")
        void toResponse_senderNotFound_senderPhotoUrlNull() {
            request.setNegotiable(true);
            NegotiationThreadEntity thread = buildThread();

            when(config.maxNegotiationRounds()).thenReturn(5);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

            var response = service.getById(SENDER_ID, THREAD_ID);

            assertThat(response.senderPhotoUrl()).isNull();
        }
    }
}
