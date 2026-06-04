package com.dony.api.requests.service;

import com.dony.api.payments.NegotiationPaymentAuthorizedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NegotiationPaymentListener")
class NegotiationPaymentListenerTest {

    @Mock private NegotiationService negotiationService;

    @InjectMocks private NegotiationPaymentListener listener;

    @Test
    @DisplayName("onPaymentAuthorized() délègue à NegotiationService.finalizeAfterPayment")
    void onPaymentAuthorized_delegatesToService() {
        UUID threadId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        NegotiationPaymentAuthorizedEvent event =
            new NegotiationPaymentAuthorizedEvent(threadId, senderId, "pi_test_123");

        listener.onPaymentAuthorized(event);

        verify(negotiationService).finalizeAfterPayment(senderId, threadId, "pi_test_123");
    }

    @Test
    @DisplayName("onPaymentAuthorized() absorbe les exceptions sans les propager")
    void onPaymentAuthorized_swallowsException() {
        UUID threadId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        NegotiationPaymentAuthorizedEvent event =
            new NegotiationPaymentAuthorizedEvent(threadId, senderId, "pi_fail");

        doThrow(new RuntimeException("service error"))
            .when(negotiationService).finalizeAfterPayment(any(), any(), any());

        // Should not propagate
        listener.onPaymentAuthorized(event);

        verify(negotiationService).finalizeAfterPayment(senderId, threadId, "pi_fail");
    }
}
