package com.playdata.calen.drive.repository;

import com.playdata.calen.drive.domain.DriveDownloadLinkAccessLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriveDownloadLinkAccessLogRepository extends JpaRepository<DriveDownloadLinkAccessLog, Long> {

    List<DriveDownloadLinkAccessLog> findTop50ByLinkIdAndOwnerIdOrderByAccessedAtDesc(Long linkId, Long ownerId);

    List<DriveDownloadLinkAccessLog> findTop50ByItemIdAndOwnerIdAndLinkIdIsNullOrderByAccessedAtDesc(Long itemId, Long ownerId);
}