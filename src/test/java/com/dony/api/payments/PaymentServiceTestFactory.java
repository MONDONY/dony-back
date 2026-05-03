package com.dony.api.payments;

import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidRepository;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.Mockito.mock;

class PaymentServiceTestFactory {
    static PaymentService bare() {
        return new PaymentService(
                mock(UserRepository.class),
                mock(BidRepository.class),
                mock(AnnouncementRepository.class),
                mock(PaymentRepository.class),
                mock(AuditService.class),
                mock(ApplicationEventPublisher.class),
                "whsec_test"
        );
    }
}
