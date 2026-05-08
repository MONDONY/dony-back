package com.dony.api.city;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface CorridorRepository extends JpaRepository<CorridorEntity, UUID> {

    boolean existsByDepartureCityAndArrivalCity(String departureCity, String arrivalCity);

    @Query("SELECT c FROM CorridorEntity c ORDER BY c.usageCount DESC LIMIT :limit")
    List<CorridorEntity> findTopByUsageCount(@Param("limit") int limit);

    @Modifying
    @Transactional
    @Query("""
        UPDATE CorridorEntity c
        SET c.usageCount = c.usageCount + 1, c.lastUsedAt = CURRENT_TIMESTAMP
        WHERE c.departureCity = :dep AND c.arrivalCity = :arr
        """)
    void incrementUsageCount(@Param("dep") String departureCity, @Param("arr") String arrivalCity);
}
