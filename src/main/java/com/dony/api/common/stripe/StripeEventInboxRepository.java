package com.dony.api.common.stripe;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface StripeEventInboxRepository extends JpaRepository<StripeEventInbox, String> {

    @Query(value = """
        SELECT * FROM stripe_event_inbox
        WHERE status IN ('RECEIVED', 'FAILED')
          AND next_attempt_at <= NOW()
        ORDER BY received_at
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<StripeEventInbox> claimNext();
}
