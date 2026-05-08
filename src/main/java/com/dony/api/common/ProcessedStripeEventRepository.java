package com.dony.api.common;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedStripeEventRepository extends JpaRepository<ProcessedStripeEvent, String> {

    boolean existsByEventId(String eventId);
}
