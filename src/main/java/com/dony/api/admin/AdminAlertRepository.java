package com.dony.api.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AdminAlertRepository extends JpaRepository<AdminAlertEntity, UUID> {

    List<AdminAlertEntity> findByTypeAndResolved(String type, boolean resolved);
}
