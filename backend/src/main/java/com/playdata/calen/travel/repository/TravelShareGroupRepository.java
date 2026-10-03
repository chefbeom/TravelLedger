package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelShareGroup;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
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

    Optional<TravelShareGroup> findByOwnerIdAndNameIgnoreCase(Long ownerId, String name);
}
