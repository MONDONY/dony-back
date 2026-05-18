package com.dony.api.common.stripe;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class StripeEventInboxRepositoryTest {

    @Autowired
    StripeEventInboxRepository repo;

    @Test
    void save_andFindById() {
        var inbox = new StripeEventInbox("evt_001", StripeWebhookSource.PAYMENTS,
                "payment_intent.succeeded", "{\"id\":\"evt_001\"}");
        repo.save(inbox);

        var found = repo.findById("evt_001").orElseThrow();
        assertThat(found.getSource()).isEqualTo(StripeWebhookSource.PAYMENTS);
        assertThat(found.getStatus()).isEqualTo(StripeEventStatus.RECEIVED);
        assertThat(found.getRetryCount()).isZero();
        assertThat(found.getEventType()).isEqualTo("payment_intent.succeeded");
    }

    @Test
    void claimNext_returnsReceivedEvent() {
        var inbox = new StripeEventInbox("evt_002", StripeWebhookSource.KYC,
                "identity.verification_session.verified", "{}");
        repo.save(inbox);

        Optional<StripeEventInbox> claimed = repo.claimNext();
        assertThat(claimed).isPresent();
        assertThat(claimed.get().getEventId()).isEqualTo("evt_002");
    }

    @Test
    void claimNext_doesNotReturnProcessed() {
        var inbox = new StripeEventInbox("evt_003", StripeWebhookSource.PAYMENTS,
                "charge.refunded", "{}");
        inbox.setStatus(StripeEventStatus.PROCESSED);
        repo.save(inbox);

        assertThat(repo.claimNext()).isEmpty();
    }
}
