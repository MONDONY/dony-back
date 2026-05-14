package com.dony.api.payments.cash.job;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.notifications.NotificationDispatcher;
import com.dony.api.payments.cash.CashCommissionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardExpirationNotificationJobTest {

    @Mock private UserRepository userRepo;
    @Mock private NotificationDispatcher notificationDispatcher;

    private CardExpirationNotificationJob job;

    @BeforeEach
    void setUp() {
        CashCommissionProperties props = new CashCommissionProperties(
                "0 */15 * * * *", 30, "0 0 * * * *", 30);
        job = new CardExpirationNotificationJob(userRepo, notificationDispatcher, props);
    }

    @Test
    void notifiesUsersWhoseCardExpiresSoon() {
        UserEntity user = new UserEntity();
        user.setCommissionPaymentMethodId("pm_abc");
        user.setCommissionCardBrand("Visa");
        user.setCommissionCardLast4("4242");
        when(userRepo.findUsersWithCardExpiringBefore(anyInt(), anyInt())).thenReturn(List.of(user));

        job.notifyExpiringCards();

        verify(notificationDispatcher).sendCardExpiringNotice(user);
    }

    @Test
    void noExpiringCardsIsNoOp() {
        when(userRepo.findUsersWithCardExpiringBefore(anyInt(), anyInt())).thenReturn(List.of());

        job.notifyExpiringCards();

        verifyNoInteractions(notificationDispatcher);
    }
}
