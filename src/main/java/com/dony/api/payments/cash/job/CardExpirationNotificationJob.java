package com.dony.api.payments.cash.job;

import com.dony.api.auth.UserRepository;
import com.dony.api.notifications.NotificationDispatcher;
import com.dony.api.payments.cash.CashCommissionProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;

@Component
public class CardExpirationNotificationJob {

    private final UserRepository userRepo;
    private final NotificationDispatcher notificationDispatcher;
    private final CashCommissionProperties props;

    public CardExpirationNotificationJob(UserRepository userRepo,
                                          NotificationDispatcher notificationDispatcher,
                                          CashCommissionProperties props) {
        this.userRepo = userRepo;
        this.notificationDispatcher = notificationDispatcher;
        this.props = props;
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "UTC")
    @Transactional(readOnly = true)
    public void notifyExpiringCards() {
        YearMonth cutoff = YearMonth.now().plusMonths(props.cardExpirationWarningDays() / 30L);
        userRepo.findUsersWithCardExpiringBefore(cutoff.getYear(), cutoff.getMonthValue())
                .forEach(notificationDispatcher::sendCardExpiringNotice);
    }
}
