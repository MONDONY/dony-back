package com.dony.api.cancellation;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationEntityTest {

    @Test
    void gettersAndSetters() {
        CancellationEntity c = new CancellationEntity();
        UUID bidId = UUID.randomUUID();
        UUID cancelledBy = UUID.randomUUID();
        OffsetDateTime deadline = OffsetDateTime.now().plusHours(24);

        c.setBidId(bidId);
        c.setCancelledBy(cancelledBy);
        c.setReason("TEST_REASON");
        c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        c.setContestationDeadline(deadline);

        c.setRefundStatus("REFUNDED");
        c.setRematchStatus("SUGGESTED");

        assertThat(c.getBidId()).isEqualTo(bidId);
        assertThat(c.getCancelledBy()).isEqualTo(cancelledBy);
        assertThat(c.getReason()).isEqualTo("TEST_REASON");
        assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.PENDING_CONFIRMATION);
        assertThat(c.getContestationDeadline()).isEqualTo(deadline);
        assertThat(c.getRefundStatus()).isEqualTo("REFUNDED");
        assertThat(c.getRematchStatus()).isEqualTo("SUGGESTED");
    }

    @Test
    void rematchSuggestionEntityGettersAndSetters() {
        RematchSuggestionEntity s = new RematchSuggestionEntity();
        UUID cancellationId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();

        s.setCancellationId(cancellationId);
        s.setAnnouncementId(announcementId);
        s.setStatus("ACCEPTED");

        assertThat(s.getCancellationId()).isEqualTo(cancellationId);
        assertThat(s.getAnnouncementId()).isEqualTo(announcementId);
        assertThat(s.getStatus()).isEqualTo("ACCEPTED");
    }
}
