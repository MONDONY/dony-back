package com.dony.api.emailotp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface EmailOtpRepository extends JpaRepository<EmailOtpEntity, UUID> {

    @Query("SELECT COUNT(e) FROM EmailOtpEntity e WHERE e.email = :email AND e.createdAt > :since")
    long countByEmailSince(@Param("email") String email, @Param("since") LocalDateTime since);

    Optional<EmailOtpEntity> findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(String email);
}
