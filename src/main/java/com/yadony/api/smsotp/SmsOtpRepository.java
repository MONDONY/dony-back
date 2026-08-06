package com.yadony.api.smsotp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface SmsOtpRepository extends JpaRepository<SmsOtpEntity, UUID> {

    @Query("SELECT COUNT(s) FROM SmsOtpEntity s WHERE s.phoneNumber = :phone AND s.createdAt > :since")
    long countByPhoneSince(@Param("phone") String phone, @Param("since") LocalDateTime since);

    Optional<SmsOtpEntity> findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(String phone);

    /**
     * Somme des essais de vérification sur tous les tokens récents d'un numéro.
     * Le budget anti-brute-force doit survivre aux renvois de code : compter par
     * token permettrait 5 essais frais à chaque POST /send.
     */
    @Query("SELECT COALESCE(SUM(s.attempts), 0) FROM SmsOtpEntity s WHERE s.phoneNumber = :phone AND s.createdAt > :since")
    long sumAttemptsByPhoneSince(@Param("phone") String phone, @Param("since") LocalDateTime since);
}
