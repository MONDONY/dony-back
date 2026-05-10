package com.dony.api.city;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CityRepository extends JpaRepository<CityEntity, Long> {

    @Query(value = """
        SELECT * FROM cities
        WHERE LOWER(name) = LOWER(:name)
        ORDER BY population DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<CityEntity> findFirstByNameIgnoreCase(@Param("name") String name);

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
