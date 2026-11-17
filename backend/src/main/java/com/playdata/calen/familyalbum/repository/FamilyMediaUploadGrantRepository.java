package com.playdata.calen.familyalbum.repository;

import com.playdata.calen.familyalbum.domain.FamilyMediaUploadGrant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyMediaUploadGrantRepository extends JpaRepository<FamilyMediaUploadGrant, Long> {
    List<FamilyMediaUploadGrant> findAllByOwnerIdOrderByIdAsc(Long ownerId);
}
