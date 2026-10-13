package com.playdata.calen.recordmap.repository;
import com.playdata.calen.recordmap.domain.RecordMapShare;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface RecordMapShareRepository extends JpaRepository<RecordMapShare, Long> {
    Optional<RecordMapShare> findByTokenHashAndActiveTrue(String tokenHash);
    Optional<RecordMapShare> findByIdAndOwnerId(Long id, Long ownerId);
    List<RecordMapShare> findAllByOwnerIdAndCollectionIdOrderByCreatedAtDesc(Long ownerId, Long collectionId);
    @Query("select distinct s.collection.id from RecordMapShare s where s.ownerId = :owner and s.active = true and s.expiresAt > :now")
    List<Long> findActiveCollectionIds(@Param("owner") Long owner, @Param("now") java.time.LocalDateTime now);
    @Modifying @Query("update RecordMapShare s set s.active = false where s.ownerId = :owner and s.active = true")
    int revokeAllActiveByOwnerId(@Param("owner") Long owner);
}
