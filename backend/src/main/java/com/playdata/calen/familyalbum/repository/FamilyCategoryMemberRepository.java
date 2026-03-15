package com.playdata.calen.familyalbum.repository;

import com.playdata.calen.familyalbum.domain.FamilyCategoryMember;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyCategoryMemberRepository extends JpaRepository<FamilyCategoryMember, Long> {

    boolean existsByCategoryIdAndUserId(Long categoryId, Long userId);

    List<FamilyCategoryMember> findAllByCategoryIdInOrderByAddedAtAscIdAsc(Collection<Long> categoryIds);
}
