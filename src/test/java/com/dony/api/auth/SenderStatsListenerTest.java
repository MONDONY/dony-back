package com.dony.api.auth;

import com.dony.api.common.AuditService;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SenderStatsListenerTest {

    @Mock private UserRepository userRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AuditService auditService;

    private SenderStatsListener listener;

    @BeforeEach
    void setUp() {
        listener = new SenderStatsListener(userRepository, bidRepository, auditService);
    }

    private static void setEntityId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private BidEntity completedBid(UUID bidId, boolean alreadyCounted) {
        BidEntity bid = new BidEntity();
        setEntityId(bid, bidId);
        bid.setStatus(BidStatus.COMPLETED);
        bid.setShipmentCounted(alreadyCounted);
        return bid;
    }

    private UserEntity sender(UUID id, int currentTotal) {
        UserEntity u = new UserEntity();
        setEntityId(u, id);
        u.setTotalShipments(currentTotal);
        return u;
    }

    @Test
    void increments_totalShipments_on_first_event() {
        UUID bidId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, false);
        UserEntity user = sender(senderId, 4);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(user));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, senderId, UUID.randomUUID()));

        assertThat(user.getTotalShipments()).isEqualTo(5);
        assertThat(bid.isShipmentCounted()).isTrue();
        verify(userRepository).save(user);
        verify(bidRepository).save(bid);
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("USER"), eq(senderId), eq("TOTAL_SHIPMENTS_INCREMENTED"),
                eq(senderId), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("bidId", bidId.toString())
                .containsEntry("newTotal", "5");
    }

    @Test
    void is_idempotent_when_flag_already_true() {
        UUID bidId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, true);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, senderId, UUID.randomUUID()));

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
        verify(bidRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void noop_when_bid_not_found() {
        UUID bidId = UUID.randomUUID();
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), UUID.randomUUID()));

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
        verify(bidRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void skips_when_bid_status_is_not_completed() {
        UUID bidId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, false);
        bid.setStatus(BidStatus.ACCEPTED);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, senderId, UUID.randomUUID()));

        assertThat(bid.isShipmentCounted()).isFalse();
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
        verify(bidRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void marks_bid_counted_and_does_not_increment_when_sender_missing() {
        UUID bidId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, false);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findById(senderId)).thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, senderId, UUID.randomUUID()));

        assertThat(bid.isShipmentCounted()).isTrue();
        verify(bidRepository).save(bid);
        verify(userRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void flags_bid_as_counted_after_increment() {
        UUID bidId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        BidEntity bid = completedBid(bidId, false);
        UserEntity user = sender(senderId, 0);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(user));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, senderId, UUID.randomUUID()));

        assertThat(bid.isShipmentCounted()).isTrue();
        assertThat(user.getTotalShipments()).isEqualTo(1);
    }
}
