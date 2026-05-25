package com.dony.api.subscriptions;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.subscriptions.dto.SubscriptionStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock TravelerSubscriptionRepository repo;
    @Mock UserRepository userRepository;
    @InjectMocks SubscriptionService service;

    final String uid = "firebase-uid";
    final UUID senderId = UUID.randomUUID();
    final UUID travelerId = UUID.randomUUID();
    UserEntity sender;

    @BeforeEach
    void setup() {
        sender = new UserEntity();
        sender.setFirstName("Awa"); sender.setLastName("K");
        try { var f = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
              f.setAccessible(true); f.set(sender, senderId); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void subscribe_createsSubscription_whenNoneExists() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(new UserEntity()));
        when(repo.findBySenderIdAndTravelerIdIncludingDeleted(senderId, travelerId)).thenReturn(Optional.empty());

        service.subscribe(uid, travelerId);

        verify(repo).save(any(TravelerSubscriptionEntity.class));
    }

    @Test
    void subscribe_reactivatesSoftDeleted_whenExists() {
        TravelerSubscriptionEntity existing = new TravelerSubscriptionEntity();
        existing.setSenderId(senderId); existing.setTravelerId(travelerId);
        existing.setDeletedAt(java.time.LocalDateTime.now());
        existing.setHasNew(true);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(new UserEntity()));
        when(repo.findBySenderIdAndTravelerIdIncludingDeleted(senderId, travelerId)).thenReturn(Optional.of(existing));

        service.subscribe(uid, travelerId);

        assertThat(existing.getDeletedAt()).isNull();
        assertThat(existing.isHasNew()).isFalse();
        verify(repo).save(existing);
    }

    @Test
    void subscribe_unknownTraveler_throwsNotFound() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(userRepository.findById(travelerId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.subscribe(uid, travelerId))
            .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void unsubscribe_softDeletesExisting() {
        TravelerSubscriptionEntity existing = new TravelerSubscriptionEntity();
        existing.setSenderId(senderId); existing.setTravelerId(travelerId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(repo.findBySenderIdAndTravelerId(senderId, travelerId)).thenReturn(Optional.of(existing));

        service.unsubscribe(uid, travelerId);

        assertThat(existing.getDeletedAt()).isNotNull();
        verify(repo).save(existing);
    }

    @Test
    void setPush_updatesFlag() {
        TravelerSubscriptionEntity existing = new TravelerSubscriptionEntity();
        existing.setSenderId(senderId); existing.setTravelerId(travelerId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(repo.findBySenderIdAndTravelerId(senderId, travelerId)).thenReturn(Optional.of(existing));

        service.setPush(uid, travelerId, true);

        assertThat(existing.isPushEnabled()).isTrue();
    }

    @Test
    void getStatus_returnsSubscribedAndPush() {
        TravelerSubscriptionEntity existing = new TravelerSubscriptionEntity();
        existing.setPushEnabled(true);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(repo.findBySenderIdAndTravelerId(senderId, travelerId)).thenReturn(Optional.of(existing));

        SubscriptionStatusResponse status = service.getStatus(uid, travelerId);

        assertThat(status.subscribed()).isTrue();
        assertThat(status.pushEnabled()).isTrue();
    }

    @Test
    void getStatus_notSubscribed_returnsFalse() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(repo.findBySenderIdAndTravelerId(senderId, travelerId)).thenReturn(Optional.empty());
        assertThat(service.getStatus(uid, travelerId).subscribed()).isFalse();
    }

    @Test
    void markSeen_resetsHasNew() {
        TravelerSubscriptionEntity existing = new TravelerSubscriptionEntity();
        existing.setHasNew(true);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        when(repo.findBySenderIdAndTravelerId(senderId, travelerId)).thenReturn(Optional.of(existing));

        service.markSeen(uid, travelerId);

        assertThat(existing.isHasNew()).isFalse();
    }

    @Test
    void getMySubscriptions_mapsProjection() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(sender));
        Object[] row = new Object[]{
            travelerId, "Ibrahima D", true, new java.math.BigDecimal("4.8"), 2L,
            false, true, UUID.randomUUID(), "Paris", "Dakar",
            new java.math.BigDecimal("8.00"), java.sql.Timestamp.valueOf(java.time.LocalDateTime.now())
        };
        when(repo.findEnrichedBySenderId(senderId)).thenReturn(List.<Object[]>of(row));

        var list = service.getMySubscriptions(uid);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).travelerName()).isEqualTo("Ibrahima D");
        assertThat(list.get(0).ongoingTripsCount()).isEqualTo(2L);
        assertThat(list.get(0).lastAnnouncement().arrivalCity()).isEqualTo("Dakar");
    }
}
