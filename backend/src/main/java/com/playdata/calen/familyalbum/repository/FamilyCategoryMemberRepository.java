package com.playdata.calen.familyalbum.repository;

import com.playdata.calen.familyalbum.domain.FamilyCategoryMember;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamilyCategoryMemberRepository extends JpaRepository<FamilyCategoryMember, Long> {

    boolean existsByCategoryIdAndUserId(Long categoryId, Long userId);

    List<FamilyCategoryMember> findAllByCategoryIdInOrderByAddedAtAscIdAsc(Collection<Long> categoryIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select member from FamilyCategoryMember member where member.category.id in :categoryIds order by member.category.id, member.id")
    List<FamilyCategoryMember> lockForWithdrawal(@Param("categoryIds") Collection<Long> categoryIds);
}
