package com.playdata.calen.drive.repository;

import com.playdata.calen.drive.domain.DriveItemVersion;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriveItemVersionRepository extends JpaRepository<DriveItemVersion, Long> {

    List<DriveItemVersion> findAllByItem_IdAndOwner_IdOrderByVersionNumberDescIdDesc(Long itemId, Long ownerId);

    long countByItem_IdAndOwner_Id(Long itemId, Long ownerId);

    void deleteAllByItem_IdIn(Collection<Long> itemIds);
}