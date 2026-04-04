package com.playdata.calen.familyalbum.repository;

import com.playdata.calen.familyalbum.domain.FamilyMediaAsset;
import com.playdata.calen.familyalbum.domain.FamilyMediaType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamilyMediaAssetRepository extends JpaRepository<FamilyMediaAsset, Long> {

    List<FamilyMediaAsset> findAllByCategoryIdIn(Collection<Long> categoryIds);

    long countByCategoryIdInAndMediaType(Collection<Long> categoryIds, FamilyMediaType mediaType);

    @Query("""
            select asset.category.id as categoryId, count(asset) as mediaCount
            from FamilyMediaAsset asset
            where asset.category.id in :categoryIds
            group by asset.category.id
            """)
    List<CategoryMediaCountView> countAccessibleMediaByCategoryIds(@Param("categoryIds") Collection<Long> categoryIds);

    @Query("""
            select asset
            from FamilyMediaAsset asset
            where asset.category.id = :categoryId
            order by coalesce(asset.capturedAt, asset.uploadedAt) desc, asset.uploadedAt desc, asset.id desc
            """)
    Page<FamilyMediaAsset> findPageByCategoryId(@Param("categoryId") Long categoryId, Pageable pageable);

    interface CategoryMediaCountView {
        Long getCategoryId();

        long getMediaCount();
    }
}
