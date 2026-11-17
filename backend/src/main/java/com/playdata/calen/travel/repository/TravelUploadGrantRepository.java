package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelUploadGrant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelUploadGrantRepository extends JpaRepository<TravelUploadGrant, Long> {
    List<TravelUploadGrant> findAllByOwnerIdOrderByIdAsc(Long ownerId);
}
