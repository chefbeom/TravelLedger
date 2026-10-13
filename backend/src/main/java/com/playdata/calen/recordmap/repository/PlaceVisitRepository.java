package com.playdata.calen.recordmap.repository;
import com.playdata.calen.recordmap.domain.PlaceVisit;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
public interface PlaceVisitRepository extends JpaRepository<PlaceVisit, Long> {
    Optional<PlaceVisit> findByIdAndOwnerId(Long id, Long ownerId);
    long countByPlaceIdAndOwnerId(Long placeId, Long ownerId);
    @Query("select v.place.id as placeId, count(v) as total from PlaceVisit v where v.ownerId = :owner and v.place.id in :ids group by v.place.id")
    List<VisitCount> countVisits(@Param("owner") Long owner, @Param("ids") Collection<Long> ids);
    interface VisitCount { Long getPlaceId(); long getTotal(); }
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from PlaceVisit v where v.id = :id and v.ownerId = :owner")
    Optional<PlaceVisit> lockOwned(@Param("id") Long id, @Param("owner") Long owner);
    List<PlaceVisit> findAllByOwnerIdAndPlaceIdInOrderByVisitDateDescVisitTimeDescIdDesc(Long ownerId, Collection<Long> ids, Pageable pageable);
    @Query("select v from PlaceVisit v where v.ownerId = :owner and v.place.id in :ids and (:fromDate is null or v.visitDate >= :fromDate) and (:toDate is null or v.visitDate <= :toDate) order by v.visitDate desc, v.visitTime desc, v.id desc")
    List<PlaceVisit> findVisibleVisits(@Param("owner") Long owner, @Param("ids") Collection<Long> ids,
        @Param("fromDate") java.time.LocalDate fromDate, @Param("toDate") java.time.LocalDate toDate, Pageable pageable);
}
