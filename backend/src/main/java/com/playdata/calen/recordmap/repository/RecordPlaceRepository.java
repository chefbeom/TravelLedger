package com.playdata.calen.recordmap.repository;

import com.playdata.calen.recordmap.domain.RecordPlace;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RecordPlaceRepository extends JpaRepository<RecordPlace, Long> {
    Optional<RecordPlace> findByIdAndOwnerId(Long id, Long ownerId);
    Optional<RecordPlace> findByOwnerIdAndSourceTravelRecordId(Long ownerId, Long sourceTravelRecordId);
    @Query("""
        select p from RecordPlace p where p.ownerId = :ownerId
        and (:collectionId is null or exists (select c.id from RecordPlace linked join linked.collections c where linked.id = p.id and c.id = :collectionId))
        and (:state = 'ALL'
            or (:state = 'VISITED' and (p.manuallyVisited = true or exists (select t.id from TravelExpenseRecord t where t.id = p.sourceTravelRecordId and t.plan.owner.id = :ownerId) or exists (select v.id from PlaceVisit v where v.place.id = p.id and v.ownerId = :ownerId)))
            or (:state = 'WANTED' and p.manuallyVisited = false and not exists (select t.id from TravelExpenseRecord t where t.id = p.sourceTravelRecordId and t.plan.owner.id = :ownerId) and not exists (select v.id from PlaceVisit v where v.place.id = p.id and v.ownerId = :ownerId)))
        and ((:fromDate is null and :toDate is null)
            or exists (select v.id from PlaceVisit v where v.place.id = p.id and (:fromDate is null or v.visitDate >= :fromDate) and (:toDate is null or v.visitDate <= :toDate))
            or exists (select t.id from TravelExpenseRecord t where t.id = p.sourceTravelRecordId and t.plan.owner.id = :ownerId and (:fromDate is null or t.expenseDate >= :fromDate) and (:toDate is null or t.expenseDate <= :toDate)))
        """)
    Page<RecordPlace> findVisible(@Param("ownerId") Long ownerId, @Param("collectionId") Long collectionId,
        @Param("state") String state, @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate, Pageable pageable);

    @Modifying @Query(value = "delete from record_map_collection_places where collection_id = :id", nativeQuery = true)
    void detachCollection(@Param("id") Long id);
}
