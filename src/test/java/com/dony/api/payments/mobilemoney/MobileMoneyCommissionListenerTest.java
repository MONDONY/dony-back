package com.dony.api.payments.mobilemoney;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.payments.cash.CashCommissionService;
import com.dony.api.payments.mobilemoney.events.BidPaidByMobileMoneyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MobileMoneyCommissionListenerTest {

    @Mock private CashCommissionService commissionService;
    @Mock private BidRepository bidRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;

    @InjectMocks private MobileMoneyCommissionListener listener;

    @Test
    void onBidPaid_travelerHasCard_chargesCommission() {
        UUID bidId      = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        BidEntity bid = new BidEntity();
        bid.setDeclaredValueEur(new BigDecimal("100.00"));

        UserEntity traveler = new UserEntity();
        traveler.setCommissionPaymentMethodId("pm_stripe_test");

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(commissionService.computeCommission(any())).thenReturn(new BigDecimal("12.00"));

        listener.onBidPaidByMobileMoney(new BidPaidByMobileMoneyEvent(bidId, travelerId));

        verify(commissionService).chargeCommissionForMobileMoney(eq(bid), eq(travelerId));
    }

    @Test
    void onBidPaid_travelerHasNoCard_logsWarningAndSkips() {
        UUID bidId      = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        BidEntity bid = new BidEntity();
        bid.setDeclaredValueEur(new BigDecimal("50.00"));

        UserEntity traveler = new UserEntity();
        traveler.setCommissionPaymentMethodId(null);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        listener.onBidPaidByMobileMoney(new BidPaidByMobileMoneyEvent(bidId, travelerId));

        verify(commissionService, never()).chargeCommissionForMobileMoney(any(), any());
    }
}
