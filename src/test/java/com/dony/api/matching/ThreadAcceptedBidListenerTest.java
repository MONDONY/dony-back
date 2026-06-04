package com.dony.api.matching;

import com.dony.api.common.AuditService;
import com.dony.api.requests.event.PackageRequestAcceptedEvent;
import com.dony.api.requests.event.PackageRequestDetailsCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ThreadAcceptedBidListenerTest {

    @Mock BidRepository bidRepository;
    @Mock AuditService auditService;
    @InjectMocks ThreadAcceptedBidListener listener;

    private static final UUID THREAD_ID = UUID.randomUUID();
    private static final UUID PACKAGE_REQUEST_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();

    private static final LocalDateTime DISCLAIMER_AT = LocalDateTime.now();

    private PackageRequestAcceptedEvent buildEvent() {
        return new PackageRequestAcceptedEvent(
                THREAD_ID, PACKAGE_REQUEST_ID,
                SENDER_ID, TRAVELER_ID,
                BigDecimal.valueOf(50),
                ANNOUNCEMENT_ID,
                BigDecimal.valueOf(5),
                "Vêtements", "CLOTHING", "pi_test",
                "Fatou Diop", "+221771234567",
                BigDecimal.valueOf(120),
                DISCLAIMER_AT, "1.2.3.4");
    }

    @Nested
    @DisplayName("onPackageRequestAccepted()")
    class OnAcceptedTests {

        @BeforeEach
        void defaultStubs() {
            lenient().when(bidRepository.findByLinkedNegotiationThreadId(THREAD_ID))
                    .thenReturn(Optional.empty());
            lenient().when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("bid créé et audité pour un thread sans bid existant")
        void createsBidAndAudits() {
            listener.onPackageRequestAccepted(buildEvent());

            ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository).save(captor.capture());
            BidEntity saved = captor.getValue();
            assertThat(saved.getAnnouncementId()).isEqualTo(ANNOUNCEMENT_ID);
            assertThat(saved.getSenderId()).isEqualTo(SENDER_ID);
            assertThat(saved.getStatus()).isEqualTo(BidStatus.ACCEPTED);
            assertThat(saved.getLinkedNegotiationThreadId()).isEqualTo(THREAD_ID);
            assertThat(saved.getRecipientName()).isEqualTo("Fatou Diop");
            assertThat(saved.getRecipientPhone()).isEqualTo("+221771234567");
            assertThat(saved.getDeclaredValueEur()).isEqualByComparingTo(BigDecimal.valueOf(120));
            assertThat(saved.getDisclaimerSignedAt()).isEqualTo(DISCLAIMER_AT);
            assertThat(saved.getDisclaimerSignedIp()).isEqualTo("1.2.3.4");
            verify(auditService).log(eq("BID"), any(), eq("CREATED_FROM_THREAD"), eq(SENDER_ID), any());
        }

        @Test
        @DisplayName("bid déjà existant → idempotence, aucune création")
        void idempotenceSkipsCreation() {
            when(bidRepository.findByLinkedNegotiationThreadId(THREAD_ID))
                    .thenReturn(Optional.of(new BidEntity()));

            listener.onPackageRequestAccepted(buildEvent());

            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("travelerAnnouncementId null → skip avec warning")
        void nullAnnouncementIdSkips() {
            PackageRequestAcceptedEvent event = new PackageRequestAcceptedEvent(
                    THREAD_ID, PACKAGE_REQUEST_ID,
                    SENDER_ID, TRAVELER_ID,
                    BigDecimal.valueOf(50),
                    null, // no announcementId
                    BigDecimal.valueOf(5),
                    "desc", "CLOTHING", "pi_test",
                    "Fatou Diop", "+221771234567",
                    BigDecimal.valueOf(120),
                    DISCLAIMER_AT, "1.2.3.4");

            listener.onPackageRequestAccepted(event);

            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("description null → utilise contentCategory comme fallback")
        void nullDescriptionFallsBackToContentCategory() {
            PackageRequestAcceptedEvent event = new PackageRequestAcceptedEvent(
                    THREAD_ID, PACKAGE_REQUEST_ID,
                    SENDER_ID, TRAVELER_ID,
                    BigDecimal.valueOf(50),
                    ANNOUNCEMENT_ID,
                    BigDecimal.valueOf(5),
                    null, "CLOTHING", "pi_test",
                    "Fatou Diop", "+221771234567",
                    BigDecimal.valueOf(120),
                    DISCLAIMER_AT, "1.2.3.4");

            listener.onPackageRequestAccepted(event);

            ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository).save(captor.capture());
            assertThat(captor.getValue().getDescription()).isEqualTo("CLOTHING");
        }
    }

    @Nested
    @DisplayName("onPackageRequestDetailsCompleted()")
    class OnDetailsCompletedTests {

        @Test
        @DisplayName("détails propagés sur le bid existant")
        void propagatesDetailsOnExistingBid() {
            BidEntity bid = new BidEntity();
            when(bidRepository.findByLinkedNegotiationThreadId(THREAD_ID))
                    .thenReturn(Optional.of(bid));
            when(bidRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PackageRequestDetailsCompletedEvent event = new PackageRequestDetailsCompletedEvent(
                    PACKAGE_REQUEST_ID, THREAD_ID, SENDER_ID,
                    "Aminata Diallo", "+221701234567",
                    BigDecimal.valueOf(100),
                    LocalDateTime.now(), "127.0.0.1");

            listener.onPackageRequestDetailsCompleted(event);

            assertThat(bid.getRecipientName()).isEqualTo("Aminata Diallo");
            assertThat(bid.getDeclaredValueEur()).isEqualTo(BigDecimal.valueOf(100));
            verify(bidRepository).save(bid);
        }

        @Test
        @DisplayName("aucun bid existant → skip silencieux")
        void noBidSkipsSilently() {
            when(bidRepository.findByLinkedNegotiationThreadId(THREAD_ID))
                    .thenReturn(Optional.empty());

            PackageRequestDetailsCompletedEvent event = new PackageRequestDetailsCompletedEvent(
                    PACKAGE_REQUEST_ID, THREAD_ID, SENDER_ID,
                    "Doe", "+33600000000", BigDecimal.valueOf(50),
                    LocalDateTime.now(), "127.0.0.1");

            listener.onPackageRequestDetailsCompleted(event);

            verify(bidRepository, never()).save(any());
        }
    }
}
