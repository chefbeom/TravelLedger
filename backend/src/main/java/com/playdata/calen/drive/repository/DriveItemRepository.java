package com.playdata.calen.drive.repository;

import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveItemType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DriveItemRepository extends JpaRepository<DriveItem, Long>, JpaSpecificationExecutor<DriveItem> {

    @Override
    @EntityGraph(attributePaths = "parent")
    Page<DriveItem> findAll(Specification<DriveItem> specification, Pageable pageable);

    @Query("select distinct lower(item.extension) from DriveItem item "
            + "where item.owner.id = :ownerId and item.itemType = :itemType "
            + "and item.extension is not null and item.extension <> '' order by lower(item.extension)")
    List<String> findAvailableExtensionsByOwnerId(
            @Param("ownerId") Long ownerId,
            @Param("itemType") DriveItemType itemType
    );

    List<DriveItem> findAllByOwner_Id(Long ownerId);

    List<DriveItem> findAllByOwner_IdOrderByLastModifiedAtDesc(Long ownerId);

    List<DriveItem> findAllByOwner_IdAndTrashedTrueOrderByDeletedAtDesc(Long ownerId);

    Optional<DriveItem> findByIdAndOwner_Id(Long id, Long ownerId);

    Optional<DriveItem> findByOwner_IdAndStoragePath(Long ownerId, String storagePath);

    Optional<DriveItem> findByOwner_IdAndSourceTypeAndSourceReference(Long ownerId, String sourceType, String sourceReference);

    List<DriveItem> findAllByOwner_IdAndSourceTypeAndSourceReference(Long ownerId, String sourceType, String sourceReference);

    long countByParent_Id(Long parentId);
}
