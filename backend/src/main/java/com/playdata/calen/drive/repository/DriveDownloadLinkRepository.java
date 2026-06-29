package com.playdata.calen.drive.repository;

import com.playdata.calen.drive.domain.DriveDownloadLink;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DriveDownloadLinkRepository extends JpaRepository<DriveDownloadLink, Long> {

    boolean existsByToken(String token);

    List<DriveDownloadLink> findAllByItem_IdAndOwner_IdOrderByCreatedAtDesc(Long itemId, Long ownerId);

    Optional<DriveDownloadLink> findByIdAndOwner_Id(Long id, Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DriveDownloadLink> findByToken(String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DriveDownloadLink link
            set link.revokedAt = :revokedAt
            where link.owner.id = :ownerId
              and link.revokedAt is null
            """)
    int revokeAllActiveByOwnerId(
            @Param("ownerId") Long ownerId,
            @Param("revokedAt") LocalDateTime revokedAt
    );

    void deleteAllByItem_IdIn(Collection<Long> itemIds);
}
