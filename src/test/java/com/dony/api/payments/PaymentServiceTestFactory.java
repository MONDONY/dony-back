package com.dony.api.payments;

import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.config.StripeConnectProperties;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidRepository;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.Mockito.mock;

class PaymentServiceTestFactory {

    /**
     * Sets the {@code id} field on a {@link com.dony.api.common.BaseEntity} subclass via reflection.
     * Walks the class hierarchy until it finds the field, consistent with how BaseEntity declares it.
     */
    static void setId(Object entity, java.util.UUID id) {
        try {
            Class<?> clazz = entity.getClass();
            java.lang.reflect.Field f = null;
            while (clazz != null) {
                try { f = clazz.getDeclaredField("id"); break; }
                catch (NoSuchFieldException e) { clazz = clazz.getSuperclass(); }
            }
            if (f == null) throw new NoSuchFieldException("id not found in class hierarchy");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException("Could not set id via reflection", e);
        }
    }

    static StripeConnectProperties defaultConnectProperties() {
        return new StripeConnectProperties(
                "4215",
                "Transport de colis entre particuliers via la plateforme Dony",
                "https://dony.app",
                "http://localhost:8080/api/v1/payments/onboarding/return",
                "http://localhost:8080/api/v1/payments/onboarding/refresh",
                "dony://stripe/onboarding/complete",
                "dony://stripe/onboarding/refresh"
        );
    }

    static PaymentService bare() {
        return new PaymentService(
                mock(UserRepository.class),
                mock(BidRepository.class),
                mock(AnnouncementRepository.class),
                mock(PaymentRepository.class),
                mock(AuditService.class),
                mock(ApplicationEventPublisher.class),
                defaultConnectProperties()
        );
    }
}
