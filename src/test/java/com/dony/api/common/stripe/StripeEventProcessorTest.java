package com.dony.api.common.stripe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StripeEventProcessorTest {

    @Mock StripeEventInboxRepository repo;
    @Mock StripeEventDispatcher dispatcher;
    @Mock AdminAlertService adminAlert;
    StripeWebhookProperties props =
            new StripeWebhookProperties(Duration.ofSeconds(10), 50, 3, Duration.ofSeconds(5), true);
    StripeEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new StripeEventProcessor(repo, dispatcher, props, adminAlert);
    }

    private StripeEventInbox makeInbox(String id, StripeEventStatus status) {
        var i = new StripeEventInbox(id, StripeWebhookSource.PAYMENTS, "test.event", "{}");
        i.setStatus(status);
        return i;
    }

    @Test
    void processOne_returnsFalse_whenNoEvent() {
        when(repo.claimNext()).thenReturn(Optional.empty());
        assertThat(processor.processOne()).isFalse();
    }

    @Test
    void processOne_setsProcessed_onSuccess() {
        var inbox = makeInbox("evt_1", StripeEventStatus.RECEIVED);
        when(repo.claimNext()).thenReturn(Optional.of(inbox));
        when(dispatcher.dispatch("{}")).thenReturn(true);

        processor.processOne();

        assertThat(inbox.getStatus()).isEqualTo(StripeEventStatus.PROCESSED);
        assertThat(inbox.getProcessedAt()).isNotNull();
    }

    @Test
    void processOne_setsSkipped_whenNoHandler() {
        var inbox = makeInbox("evt_2", StripeEventStatus.RECEIVED);
        when(repo.claimNext()).thenReturn(Optional.of(inbox));
        when(dispatcher.dispatch(any())).thenReturn(false);

        processor.processOne();

        assertThat(inbox.getStatus()).isEqualTo(StripeEventStatus.SKIPPED);
    }

    @Test
    void processOne_setsFailed_withBackoff_onFirstFailure() {
        var inbox = makeInbox("evt_3", StripeEventStatus.RECEIVED);
        when(repo.claimNext()).thenReturn(Optional.of(inbox));
        when(dispatcher.dispatch(any())).thenThrow(new RuntimeException("stripe timeout"));

        processor.processOne();

        assertThat(inbox.getStatus()).isEqualTo(StripeEventStatus.FAILED);
        assertThat(inbox.getRetryCount()).isEqualTo(1);
        assertThat(inbox.getNextAttemptAt()).isAfter(Instant.now());
    }

    @Test
    void processOne_setsDeadLetter_afterMaxRetries() {
        var inbox = makeInbox("evt_4", StripeEventStatus.FAILED);
        inbox.setRetryCount(2); // maxRetries=3, ce sera le 3ème échec
        when(repo.claimNext()).thenReturn(Optional.of(inbox));
        when(dispatcher.dispatch(any())).thenThrow(new RuntimeException("persist error"));

        processor.processOne();

        assertThat(inbox.getStatus()).isEqualTo(StripeEventStatus.DEAD_LETTER);
        verify(adminAlert).raise(eq("STRIPE_DEAD_LETTER"), any(), any());
    }
}
