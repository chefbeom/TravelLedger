package com.playdata.calen.drive.repository;

import com.playdata.calen.drive.domain.DriveShare;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriveShareRepository extends JpaRepository<DriveShare, Long> {

    List<DriveShare> findAllByRecipient_IdOrderByCreatedAtDesc(Long recipientId);

    List<DriveShare> findAllByOwner_IdOrderByCreatedAtDesc(Long ownerId);

    List<DriveShare> findAllByItem_Id(Long itemId);

    Optional<DriveShare> findByItem_IdAndRecipient_Id(Long itemId, Long recipientId);

    void deleteByItem_IdAndRecipient_Id(Long itemId, Long recipientId);

    long countByItem_Id(Long itemId);
}
