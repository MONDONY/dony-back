package com.dony.api.requests.repository;

import com.dony.api.requests.entity.PackageRequestReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PackageRequestReportRepository extends JpaRepository<PackageRequestReportEntity, UUID> {

    boolean existsByPackageRequestIdAndReporterId(UUID packageRequestId, UUID reporterId);
}
