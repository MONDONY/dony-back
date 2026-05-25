package com.dony.api.subscriptions;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TravelerSubscriptionRepository extends JpaRepository<TravelerSubscriptionEntity, UUID> {

    boolean existsBySenderIdAndTravelerId(UUID senderId, UUID travelerId);

    @Query("SELECT ts.senderId FROM TravelerSubscriptionEntity ts WHERE ts.travelerId = :travelerId")
    List<UUID> findSenderIdsByTravelerId(@Param("travelerId") UUID travelerId);

    List<TravelerSubscriptionEntity> findAllBySenderId(UUID senderId);
}
