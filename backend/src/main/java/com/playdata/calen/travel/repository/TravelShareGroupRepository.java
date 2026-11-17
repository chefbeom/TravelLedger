package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelShareGroup;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TravelShareGroupRepository extends JpaRepository<TravelShareGroup, Long> {

    @EntityGraph(attributePaths = "owner")
    @Query("""
            select g from TravelShareGroup g where g.owner.id = :userId
            or exists (select m.id from TravelShareGroupMember m where m.group = g and m.member.id = :userId)
            order by g.updatedAt desc, g.id desc
            """)
    List<TravelShareGroup> findAccessibleGroups(@Param("userId") Long userId);

    List<TravelShareGroup> findAllByOwnerIdOrderByUpdatedAtDescIdDesc(Long ownerId);

    Optional<TravelShareGroup> findByIdAndOwnerId(Long id, Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from TravelShareGroup g where g.id = :id")
    Optional<TravelShareGroup> findForShareUpdate(@Param("id") Long id);

    Optional<TravelShareGroup> findByOwnerIdAndNameIgnoreCase(Long ownerId, String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from TravelShareGroup g where g.owner.id = :ownerId order by g.id")
    List<TravelShareGroup> lockOwnedForWithdrawal(@Param("ownerId") Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from TravelShareGroup g where g.owner.id = :ownerId and lower(g.name) = lower(:name)")
    Optional<TravelShareGroup> findOwnedByNameForUpdate(@Param("ownerId") Long ownerId, @Param("name") String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from TravelShareGroup g where g.id = :id and g.owner.id = :ownerId")
    Optional<TravelShareGroup> findOwnedByIdForUpdate(@Param("ownerId") Long ownerId, @Param("id") Long id);

    @Query("select distinct g.owner.id from TravelShareGroup g where g.owner.id in :ownerIds and lower(g.name) = lower(:name)")
    List<Long> findOwnersWithName(@Param("ownerIds") Collection<Long> ownerIds, @Param("name") String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from TravelShareGroup g where g.owner.id = :ownerId and lower(g.name) = lower(:name)")
    List<TravelShareGroup> lockNameConflicts(@Param("ownerId") Long ownerId, @Param("name") String name);
}
