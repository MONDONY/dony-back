package com.dony.api.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserDeviceJpaRepository extends JpaRepository<UserDeviceEntity, UUID> {

    List<UserDeviceEntity> findByUserIdOrderByLastSeenAtDesc(UUID userId);

    Optional<UserDeviceEntity> findByUserIdAndDeviceId(UUID userId, String deviceId);

    @Modifying
    @Query("DELETE FROM UserDeviceEntity d WHERE d.userId = :userId AND d.deviceId = :deviceId")
    int deleteByUserIdAndDeviceId(UUID userId, String deviceId);

    @Modifying
    @Query("DELETE FROM UserDeviceEntity d WHERE d.userId = :userId AND d.deviceId <> :deviceId")
    void deleteByUserIdAndDeviceIdNot(UUID userId, String deviceId);
}
