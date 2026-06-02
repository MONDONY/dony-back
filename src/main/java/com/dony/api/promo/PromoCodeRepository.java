package com.dony.api.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface PromoCodeRepository extends JpaRepository<PromoCodeEntity, UUID> {

    Optional<PromoCodeEntity> findByCode(String code);

    /** Verrou pessimiste pour incrémenter redeemedCount sans race. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PromoCodeEntity p WHERE p.id = :id")
    Optional<PromoCodeEntity> findByIdForUpdate(UUID id);
}
