package com.dony.api.disputes;

import com.dony.api.common.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DisputeServiceTest {

    @Mock private DisputeRepository disputeRepository;
    @Mock private AuditService auditService;

    private DisputeService service;

    private static final UUID BID_ID      = UUID.randomUUID();
    private static final UUID SENDER_ID   = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DisputeService(disputeRepository, auditService);
    }

    @Nested
    class OpenSenderNoShowDispute {

        @Test
        void success_createsDisputeWithCorrectFields() {
            when(disputeRepository.findByBidId(BID_ID)).thenReturn(Optional.empty());
            when(disputeRepository.save(any())).thenAnswer(inv -> {
                DisputeEntity d = inv.getArgument(0);
                ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
                return d;
            });

            DisputeEntity result = service.openSenderNoShowDispute(BID_ID, SENDER_ID, TRAVELER_ID);

            assertThat(result.getBidId()).isEqualTo(BID_ID);
            assertThat(result.getSenderId()).isEqualTo(SENDER_ID);
            assertThat(result.getTravelerId()).isEqualTo(TRAVELER_ID);
            assertThat(result.getType()).isEqualTo("SENDER_NO_SHOW_CONTESTED");
            assertThat(result.getStatus()).isEqualTo("OPEN");
            assertThat(result.isRefundFrozen()).isTrue();

            verify(disputeRepository).save(any(DisputeEntity.class));
            verify(auditService).log(eq("DISPUTE"), any(UUID.class),
                    eq("SENDER_NO_SHOW_DISPUTE_OPENED"), eq(SENDER_ID), any(Map.class));
        }

        @Test
        void idempotent_returnsExistingOpenDispute_noSaveNoAudit() {
            DisputeEntity existing = new DisputeEntity();
            ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
            existing.setBidId(BID_ID);
            existing.setSenderId(SENDER_ID);
            existing.setTravelerId(TRAVELER_ID);
            existing.setType("SENDER_NO_SHOW_CONTESTED");
            existing.setStatus("OPEN");
            existing.setRefundFrozen(true);

            when(disputeRepository.findByBidId(BID_ID)).thenReturn(Optional.of(existing));

            DisputeEntity result = service.openSenderNoShowDispute(BID_ID, SENDER_ID, TRAVELER_ID);

            assertThat(result).isSameAs(existing);
            verify(disputeRepository, never()).save(any());
            verifyNoInteractions(auditService);
        }
    }
}
