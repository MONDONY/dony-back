package com.dony.api.notifications;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationPrefsServiceTest {

    @Mock NotificationPrefsJpaRepository repository;
    @Mock UserRepository userRepository;
    @InjectMocks NotificationPrefsService service;

    private static final String FIREBASE_UID = "uid-test";
    private static final UUID USER_ID = UUID.randomUUID();
    private final UserEntity user = new UserEntity();

    @BeforeEach
    void setUp() {
        user.setFirebaseUid(FIREBASE_UID);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        lenient().when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
    }

    @Test
    void getPrefs_noRowExists_returnsDefaults() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());
        NotificationPrefsDto result = service.getPrefs(FIREBASE_UID);
        assertThat(result.pushActivityBids()).isTrue();
        assertThat(result.pushActivityNegotiations()).isTrue();
        assertThat(result.pushMessages()).isTrue();
        assertThat(result.pushTripReminder()).isTrue();
        assertThat(result.pushPromo()).isFalse();
    }

    @Test
    void getPrefs_rowExists_returnsStoredValues() {
        when(repository.findById(USER_ID)).thenReturn(Optional.of(buildEntity(false, false, false, false, true)));
        NotificationPrefsDto result = service.getPrefs(FIREBASE_UID);
        assertThat(result.pushActivityBids()).isFalse();
        assertThat(result.pushPromo()).isTrue();
    }

    @Test
    void upsert_noRowExists_createsRow() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());
        service.upsert(FIREBASE_UID, new NotificationPrefsDto(false, true, true, false, true));
        ArgumentCaptor<NotificationPrefsEntity> captor = ArgumentCaptor.forClass(NotificationPrefsEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().isPushActivityBids()).isFalse();
        assertThat(captor.getValue().isPushPromo()).isTrue();
    }

    @Test
    void upsert_rowExists_updatesRow() {
        NotificationPrefsEntity existing = buildEntity(true, true, true, true, false);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(existing));
        service.upsert(FIREBASE_UID, new NotificationPrefsDto(false, false, false, false, true));
        ArgumentCaptor<NotificationPrefsEntity> captor = ArgumentCaptor.forClass(NotificationPrefsEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isPushActivityBids()).isFalse();
        assertThat(captor.getValue().isPushPromo()).isTrue();
    }

    @Test
    void isAllowed_criticalTypes_alwaysReturnTrue() {
        assertThat(service.isAllowed(USER_ID, "PAYMENT_RELEASED")).isTrue();
        assertThat(service.isAllowed(USER_ID, "DELIVERY_CONFIRMED")).isTrue();
        assertThat(service.isAllowed(USER_ID, "DISPUTE_OPENED")).isTrue();
        verifyNoInteractions(repository);
    }

    @Test
    void isAllowed_nullType_returnsTrue() {
        assertThat(service.isAllowed(USER_ID, null)).isTrue();
        verifyNoInteractions(repository);
    }

    @Test
    void isAllowed_unknownType_returnsTrue() {
        assertThat(service.isAllowed(USER_ID, "UNKNOWN_TYPE")).isTrue();
    }

    @Test
    void isAllowed_noRowExists_returnsTrueByDefault() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());
        assertThat(service.isAllowed(USER_ID, "BID_CREATED")).isTrue();
    }

    @Test
    void isAllowed_bidType_withPrefDisabled_returnsFalse() {
        when(repository.findById(USER_ID)).thenReturn(Optional.of(buildEntity(false, true, true, true, false)));
        assertThat(service.isAllowed(USER_ID, "BID_CREATED")).isFalse();
        assertThat(service.isAllowed(USER_ID, "BID_ACCEPTED")).isFalse();
        assertThat(service.isAllowed(USER_ID, "TRIP_CANCELLED")).isFalse();
    }

    @Test
    void isAllowed_negotiationType_withPrefDisabled_returnsFalse() {
        when(repository.findById(USER_ID)).thenReturn(Optional.of(buildEntity(true, false, true, true, false)));
        assertThat(service.isAllowed(USER_ID, "negotiation_started")).isFalse();
        assertThat(service.isAllowed(USER_ID, "request_accepted")).isFalse();
    }

    @Test
    void isAllowed_newMessage_withPrefDisabled_returnsFalse() {
        when(repository.findById(USER_ID)).thenReturn(Optional.of(buildEntity(true, true, false, true, false)));
        assertThat(service.isAllowed(USER_ID, "NEW_MESSAGE")).isFalse();
    }

    @Test
    void isAllowed_tripReminder_withPrefDisabled_returnsFalse() {
        when(repository.findById(USER_ID)).thenReturn(Optional.of(buildEntity(true, true, true, false, false)));
        assertThat(service.isAllowed(USER_ID, "TRIP_IN_PROGRESS")).isFalse();
    }

    private NotificationPrefsEntity buildEntity(boolean bids, boolean negs, boolean msgs, boolean reminder, boolean promo) {
        NotificationPrefsEntity e = new NotificationPrefsEntity();
        e.setUserId(USER_ID);
        e.setPushActivityBids(bids);
        e.setPushActivityNegotiations(negs);
        e.setPushMessages(msgs);
        e.setPushTripReminder(reminder);
        e.setPushPromo(promo);
        return e;
    }
}
