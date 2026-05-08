package com.dony.api.city;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CityRepository extends JpaRepository<CityEntity, Long> {

    @Query(value = """
        SELECT * FROM cities
        WHERE name ILIKE :prefix
           OR name ILIKE :anywhere
        ORDER BY
            CASE WHEN name ILIKE :prefix THEN 0 ELSE 1 END,
            population DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<CityEntity> searchByName(
        @Param("prefix")   String prefix,
        @Param("anywhere") String anywhere,
        @Param("limit")    int limit
    );

    default List<CityEntity> searchByName(String query, int limit) {
        String q = query.trim();
        return searchByName(q + "%", "%" + q + "%", limit);
    }
}
