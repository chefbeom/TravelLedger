package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelShareGroupMember;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelShareGroupMemberRepository extends JpaRepository<TravelShareGroupMember, Long> {

    List<TravelShareGroupMember> findAllByGroupOwnerIdOrderByGroupIdAscIdAsc(Long ownerId);

    List<TravelShareGroupMember> findAllByGroupIdOrderByIdAsc(Long groupId);

    void deleteAllByGroupId(Long groupId);
}
