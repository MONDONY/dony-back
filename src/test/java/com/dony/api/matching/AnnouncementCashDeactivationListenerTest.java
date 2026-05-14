package com.dony.api.matching;

import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.payments.cash.event.CommissionMethodDetachedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnnouncementCashDeactivationListenerTest {

    @Mock private AnnouncementRepository repo;

    private AnnouncementCashDeactivationListener listener;

    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new AnnouncementCashDeactivationListener(repo);
    }

    private AnnouncementEntity annWith(PaymentMethod... methods) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setAcceptedPaymentMethods(EnumSet.copyOf(List.of(methods)));
        return a;
    }

    @Test
    void removeCashFromAllActiveAnnouncements() {
        AnnouncementEntity a1 = annWith(PaymentMethod.STRIPE, PaymentMethod.CASH);
        AnnouncementEntity a2 = annWith(PaymentMethod.CASH);
        when(repo.findActiveByTravelerId(travelerId)).thenReturn(List.of(a1, a2));

        listener.onCommissionMethodDetached(new CommissionMethodDetachedEvent(travelerId));

        assertThat(a1.getAcceptedPaymentMethods()).containsExactly(PaymentMethod.STRIPE);
        assertThat(a2.getAcceptedPaymentMethods()).containsExactly(PaymentMethod.STRIPE);
        verify(repo, times(2)).save(any(AnnouncementEntity.class));
    }

    @Test
    void announcementWithOnlyStripeIsUntouched() {
        AnnouncementEntity a = annWith(PaymentMethod.STRIPE);
        when(repo.findActiveByTravelerId(travelerId)).thenReturn(List.of(a));

        listener.onCommissionMethodDetached(new CommissionMethodDetachedEvent(travelerId));

        assertThat(a.getAcceptedPaymentMethods()).containsExactly(PaymentMethod.STRIPE);
        verify(repo, never()).save(any());
    }

    @Test
    void noAnnouncementsIsNoOp() {
        when(repo.findActiveByTravelerId(travelerId)).thenReturn(List.of());

        listener.onCommissionMethodDetached(new CommissionMethodDetachedEvent(travelerId));

        verify(repo, never()).save(any());
    }
}
