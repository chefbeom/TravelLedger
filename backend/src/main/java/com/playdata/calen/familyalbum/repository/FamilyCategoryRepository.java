package com.playdata.calen.familyalbum.repository;

import com.playdata.calen.familyalbum.domain.FamilyCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamilyCategoryRepository extends JpaRepository<FamilyCategory, Long> {

    @Query("""
            select distinct category
            from FamilyCategory category
            left join category.members member
            where category.owner.id = :userId or member.user.id = :userId
            order by category.createdAt desc, category.id desc
            """)
    List<FamilyCategory> findAccessibleCategories(@Param("userId") Long userId);
}
