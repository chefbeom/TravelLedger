package com.playdata.calen.recordmap.repository;
import com.playdata.calen.recordmap.domain.PlacePhoto;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PlacePhotoRepository extends JpaRepository<PlacePhoto, Long> {
    Optional<PlacePhoto> findByIdAndOwnerId(Long id, Long ownerId);
    List<PlacePhoto> findAllByOwnerIdAndVisitIdInOrderByIdAsc(Long ownerId, Collection<Long> ids);
    long countByVisitIdAndOwnerId(Long visitId, Long ownerId);
}
