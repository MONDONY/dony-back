package com.yadony.api.signalements;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReportPhotoRepository extends JpaRepository<ReportPhotoEntity, UUID> {

    List<ReportPhotoEntity> findByReportIdOrderByCreatedAtAsc(UUID reportId);

    List<ReportPhotoEntity> findByReportIdInOrderByCreatedAtAsc(Collection<UUID> reportIds);
}
