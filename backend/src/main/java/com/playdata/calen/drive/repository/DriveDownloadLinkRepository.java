package com.playdata.calen.drive.repository;

import com.playdata.calen.drive.domain.DriveDownloadLink;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface DriveDownloadLinkRepository extends JpaRepository<DriveDownloadLink, Long> {

    boolean existsByToken(String token);

    List<DriveDownloadLink> findAllByItem_IdAndOwner_IdOrderByCreatedAtDesc(Long itemId, Long ownerId);

    Optional<DriveDownloadLink> findByIdAndOwner_Id(Long id, Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DriveDownloadLink> findByToken(String token);

    void deleteAllByItem_IdIn(Collection<Long> itemIds);
}
