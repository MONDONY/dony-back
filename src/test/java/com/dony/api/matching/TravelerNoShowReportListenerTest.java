package com.dony.api.matching;

import com.dony.api.cancellation.events.TravelerNoShowReportedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TravelerNoShowReportListenerTest {

    @Mock private NoShowService noShowService;

    @InjectMocks private TravelerNoShowReportListener listener;

    @Test
    void onTravelerNoShowReported_delegatesToNoShowServiceWithSenderReportSource() {
        UUID bidId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();

        listener.onTravelerNoShowReported(new TravelerNoShowReportedEvent(bidId, senderId));

        verify(noShowService).recordTravelerNoShow(bidId, "sender_report");
    }
}
