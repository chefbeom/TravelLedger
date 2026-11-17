package com.playdata.calen.familyalbum.repository;

import com.playdata.calen.familyalbum.domain.FamilyCategory;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    List<FamilyCategory> findAllByOwnerIdOrderByIdAsc(Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select category from FamilyCategory category where category.id = :id")
    Optional<FamilyCategory> lockById(@Param("id") Long id);

    @Query("""
            select category.id from FamilyCategory category
            where category.owner.id = :userId
               or exists (select album.id from FamilyAlbum album where album.category = category and album.owner.id = :userId)
               or exists (select asset.id from FamilyMediaAsset asset where asset.category = category and asset.owner.id = :userId)
            order by category.id
            """)
    List<Long> findRelatedCategoryIdsForWithdrawal(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select category from FamilyCategory category where category.id in :ids order by category.id")
    List<FamilyCategory> lockCategoriesForWithdrawal(@Param("ids") Collection<Long> ids);
}
