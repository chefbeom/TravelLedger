package com.playdata.calen.drive.repository;

import com.playdata.calen.drive.domain.DriveDownloadLinkAccessLog;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DriveDownloadLinkAccessLogRepository extends JpaRepository<DriveDownloadLinkAccessLog, Long> {

    List<DriveDownloadLinkAccessLog> findTop50ByLinkIdAndOwnerIdOrderByAccessedAtDesc(Long linkId, Long ownerId);

    List<DriveDownloadLinkAccessLog> findTop50ByItemIdAndOwnerIdAndLinkIdIsNullOrderByAccessedAtDesc(Long itemId, Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select log from DriveDownloadLinkAccessLog log
            where log.directShareAnonymized = false and log.linkId is null
              and log.status like :prefix escape '!' order by log.id
            """)
    List<DriveDownloadLinkAccessLog> findDirectShareLogsToAnonymize(@Param("prefix") String prefix, Pageable page);
}
