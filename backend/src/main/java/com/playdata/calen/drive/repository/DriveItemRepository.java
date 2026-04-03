package com.playdata.calen.drive.repository;

import com.playdata.calen.drive.domain.DriveItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriveItemRepository extends JpaRepository<DriveItem, Long> {

    List<DriveItem> findAllByOwner_Id(Long ownerId);

    List<DriveItem> findAllByOwner_IdOrderByLastModifiedAtDesc(Long ownerId);

    List<DriveItem> findAllByOwner_IdAndTrashedTrueOrderByDeletedAtDesc(Long ownerId);

    Optional<DriveItem> findByIdAndOwner_Id(Long id, Long ownerId);

    Optional<DriveItem> findByOwner_IdAndStoragePath(Long ownerId, String storagePath);

    long countByParent_Id(Long parentId);
}
