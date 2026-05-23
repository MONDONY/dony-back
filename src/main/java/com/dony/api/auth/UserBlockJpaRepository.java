package com.dony.api.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserBlockJpaRepository extends JpaRepository<UserBlockEntity, UUID> {

    boolean existsByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    List<UserBlockEntity> findByBlockerIdOrderByCreatedAtDesc(UUID blockerId);

    @Modifying
    @Query("DELETE FROM UserBlockEntity b WHERE b.blockerId = :blockerId AND b.blockedId = :blockedId")
    int deleteByBlockerIdAndBlockedId(@Param("blockerId") UUID blockerId, @Param("blockedId") UUID blockedId);

    /** True si A a bloqué B OU B a bloqué A (relation bidirectionnelle). */
    @Query("SELECT COUNT(b) > 0 FROM UserBlockEntity b WHERE " +
           "(b.blockerId = :a AND b.blockedId = :b) OR (b.blockerId = :b AND b.blockedId = :a)")
    boolean existsBetween(@Param("a") UUID a, @Param("b") UUID b);

    /** IDs des utilisateurs en relation de blocage avec :userId (dans les deux sens). */
    @Query("SELECT b.blockedId FROM UserBlockEntity b WHERE b.blockerId = :userId " +
           "UNION SELECT b.blockerId FROM UserBlockEntity b WHERE b.blockedId = :userId")
    List<UUID> findBlockedRelationIds(@Param("userId") UUID userId);
}
