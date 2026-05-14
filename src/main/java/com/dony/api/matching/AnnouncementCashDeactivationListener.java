package com.dony.api.matching;

import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.payments.cash.event.CommissionMethodDetachedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

@Component
public class AnnouncementCashDeactivationListener {

    private final AnnouncementRepository repo;

    public AnnouncementCashDeactivationListener(AnnouncementRepository repo) {
        this.repo = repo;
    }

    @EventListener
    @Transactional
    public void onCommissionMethodDetached(CommissionMethodDetachedEvent event) {
        repo.findActiveByTravelerId(event.travelerId()).forEach(ann -> {
            Set<PaymentMethod> methods = ann.getAcceptedPaymentMethods();
            if (!methods.contains(PaymentMethod.CASH)) return;

            Set<PaymentMethod> updated = EnumSet.copyOf(methods);
            updated.remove(PaymentMethod.CASH);
            if (updated.isEmpty()) updated.add(PaymentMethod.STRIPE);
            ann.setAcceptedPaymentMethods(updated);
            repo.save(ann);
        });
    }
}
