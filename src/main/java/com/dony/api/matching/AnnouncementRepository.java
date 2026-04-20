package com.dony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<AnnouncementEntity, UUID> {
}
