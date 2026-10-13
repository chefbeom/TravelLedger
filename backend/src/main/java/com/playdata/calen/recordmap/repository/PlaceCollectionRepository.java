package com.playdata.calen.recordmap.repository;
import com.playdata.calen.recordmap.domain.PlaceCollection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PlaceCollectionRepository extends JpaRepository<PlaceCollection, Long> {
    List<PlaceCollection> findAllByOwnerIdOrderByCreatedAtDescIdDesc(Long ownerId);
    Optional<PlaceCollection> findByIdAndOwnerId(Long id, Long ownerId);
}
