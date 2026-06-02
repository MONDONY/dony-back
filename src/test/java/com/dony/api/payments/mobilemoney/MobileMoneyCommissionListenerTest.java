package com.dony.api.payments.mobilemoney;

import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.payments.cash.CashCommissionService;
import com.dony.api.payments.mobilemoney.events.BidPaidByMobileMoneyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MobileMoneyCommissionListenerTest {

    @Mock private CashCommissionService commissionService;
    @Mock private BidRepository bidRepository;

    @InjectMocks private MobileMoneyCommissionListener listener;

    @Test
    void onBidPaid_delegatesToChargeCommissionAuto() {
        UUID bidId      = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        BidEntity bid = new BidEntity();
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onBidPaidByMobileMoney(new BidPaidByMobileMoneyEvent(bidId, travelerId));

        verify(commissionService).chargeCommissionAuto(eq(bid), eq(travelerId));
    }

    @Test
    void onBidPaid_bidNotFound_doesNothing() {
        UUID bidId      = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        listener.onBidPaidByMobileMoney(new BidPaidByMobileMoneyEvent(bidId, travelerId));

        verifyNoInteractions(commissionService);
    }
}
