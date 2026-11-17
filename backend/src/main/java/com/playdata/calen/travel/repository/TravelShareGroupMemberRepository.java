package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelShareGroupMember;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TravelShareGroupMemberRepository extends JpaRepository<TravelShareGroupMember, Long> {

    boolean existsByGroupIdAndMemberId(Long groupId, Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select member from TravelShareGroupMember member where member.group.id = :groupId and member.member.id = :userId")
    Optional<TravelShareGroupMember> findForShareUpdate(@Param("groupId") Long groupId, @Param("userId") Long userId);

    @EntityGraph(attributePaths = "member")
    List<TravelShareGroupMember> findAllByGroupIdInOrderByIdAsc(List<Long> groupIds);

    @EntityGraph(attributePaths = {"group", "member"})
    List<TravelShareGroupMember> findAllByGroupOwnerIdOrderByGroupIdAscIdAsc(Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from TravelShareGroupMember m where m.group.id in :groupIds order by m.group.id, m.id")
    List<TravelShareGroupMember> lockForWithdrawal(@Param("groupIds") Collection<Long> groupIds);

    List<TravelShareGroupMember> findAllByGroupIdOrderByIdAsc(Long groupId);

    @Modifying(flushAutomatically = true)
    @Query("delete from TravelShareGroupMember member where member.group.id = :groupId")
    void deleteAllByGroupId(@Param("groupId") Long groupId);
}
