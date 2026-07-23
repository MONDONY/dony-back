package com.dony.api.payments;

import com.dony.api.requests.event.NegotiationCancelledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NegotiationCancelledEscrowListenerTest {

    @Mock private PaymentService paymentService;

    private NegotiationCancelledEscrowListener listener;

    private final UUID threadId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID byUserId = UUID.randomUUID();
    private final UUID toUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new NegotiationCancelledEscrowListener(paymentService);
    }

    private NegotiationCancelledEvent event(boolean releaseEscrow) {
        return new NegotiationCancelledEvent(threadId, requestId, byUserId, toUserId, "Alice", releaseEscrow);
    }

    @Test
    @DisplayName("releaseEscrow=true → cancelNegotiationEscrow(threadId) appelé")
    void releaseTrue_cancelsEscrow() {
        when(paymentService.cancelNegotiationEscrow(threadId)).thenReturn(true);

        listener.onNegotiationCancelled(event(true));

        verify(paymentService).cancelNegotiationEscrow(threadId);
    }

    @Test
    @DisplayName("releaseEscrow=false (OPEN / AWAITING_TRIP) → aucun appel Stripe")
    void releaseFalse_doesNothing() {
        listener.onNegotiationCancelled(event(false));

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("cancelNegotiationEscrow=false (hold non annulable) → loggé, pas d'exception")
    void releaseFails_isLoggedNotThrown() {
        when(paymentService.cancelNegotiationEscrow(threadId)).thenReturn(false);

        assertThatNoException().isThrownBy(() -> listener.onNegotiationCancelled(event(true)));

        verify(paymentService).cancelNegotiationEscrow(threadId);
    }

    @Test
    @DisplayName("erreur runtime du service → avalée (la cancellation est déjà commitée)")
    void serviceThrows_isSwallowed() {
        when(paymentService.cancelNegotiationEscrow(threadId))
            .thenThrow(new RuntimeException("stripe boom"));

        assertThatNoException().isThrownBy(() -> listener.onNegotiationCancelled(event(true)));

        verify(paymentService).cancelNegotiationEscrow(threadId);
    }
}
