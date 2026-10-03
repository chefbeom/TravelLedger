package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelShareGroupMember;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface TravelShareGroupMemberRepository extends JpaRepository<TravelShareGroupMember, Long> {

    boolean existsByGroupIdAndMemberId(Long groupId, Long memberId);

    @EntityGraph(attributePaths = "member")
    List<TravelShareGroupMember> findAllByGroupIdInOrderByIdAsc(List<Long> groupIds);

    List<TravelShareGroupMember> findAllByGroupOwnerIdOrderByGroupIdAscIdAsc(Long ownerId);

    List<TravelShareGroupMember> findAllByGroupIdOrderByIdAsc(Long groupId);

    void deleteAllByGroupId(Long groupId);
}
