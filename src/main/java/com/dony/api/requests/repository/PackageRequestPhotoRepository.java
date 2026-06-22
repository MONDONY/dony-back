package com.dony.api.requests.repository;

import com.dony.api.requests.entity.PackageRequestPhotoEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PackageRequestPhotoRepository extends JpaRepository<PackageRequestPhotoEntity, UUID> {

    List<PackageRequestPhotoEntity> findByPackageRequestIdOrderByPositionAsc(UUID packageRequestId);

    Optional<PackageRequestPhotoEntity> findFirstByPackageRequestIdOrderByPositionAsc(UUID packageRequestId);

    void deleteByPackageRequestId(UUID packageRequestId);

    /** Batch-load photos for multiple package requests ordered by packageRequestId, position. */
    List<PackageRequestPhotoEntity> findByPackageRequestIdInOrderByPositionAsc(Collection<UUID> packageRequestIds);
}
