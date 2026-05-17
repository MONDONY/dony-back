package com.dony.api.common.stripe;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StripeWebhookIngestServiceTest {

    @Mock
    StripeEventInboxRepository repo;

    StripeWebhookIngestService service;

    @BeforeEach
    void setUp() {
        service = new StripeWebhookIngestService(repo, "whsec_payments", "whsec_kyc");
    }

    @Test
    void ingest_skipsAlreadyPresentEvent() throws Exception {
        var fakeEvent = mockEvent("evt_dup", "payment_intent.succeeded");

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(fakeEvent);
            when(repo.existsById("evt_dup")).thenReturn(true);

            service.ingest("{}", "sig", StripeWebhookSource.PAYMENTS);

            verify(repo, never()).save(any());
        }
    }

    @Test
    void ingest_savesNewEvent() throws Exception {
        var fakeEvent = mockEvent("evt_new", "payment_intent.succeeded");

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(fakeEvent);
            when(repo.existsById("evt_new")).thenReturn(false);
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.ingest("{}", "sig", StripeWebhookSource.PAYMENTS);

            verify(repo).save(argThat(e -> "evt_new".equals(e.getEventId())));
        }
    }

    @Test
    void ingest_throwsOnInvalidSignature() throws Exception {
        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(any(), any(), any()))
              .thenThrow(new SignatureVerificationException("bad sig", "sig_header"));

            assertThatThrownBy(() -> service.ingest("{}", "bad", StripeWebhookSource.PAYMENTS))
                    .isInstanceOf(com.dony.api.common.DonyBusinessException.class);

            verify(repo, never()).save(any());
        }
    }

    private Event mockEvent(String id, String type) {
        var event = mock(Event.class);
        lenient().when(event.getId()).thenReturn(id);
        lenient().when(event.getType()).thenReturn(type);
        return event;
    }
}
