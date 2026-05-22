package com.dony.api.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationPrefsJpaRepository
        extends JpaRepository<NotificationPrefsEntity, UUID> {}
