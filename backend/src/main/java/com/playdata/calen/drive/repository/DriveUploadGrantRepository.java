package com.playdata.calen.drive.repository;

import com.playdata.calen.drive.domain.DriveUploadGrant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriveUploadGrantRepository extends JpaRepository<DriveUploadGrant, Long> {
    List<DriveUploadGrant> findAllByOwnerIdOrderByIdAsc(Long ownerId);
}
